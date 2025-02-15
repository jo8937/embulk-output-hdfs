package org.embulk.output.hdfs.client;

import com.google.common.base.Optional;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Options;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.Trash;
import org.apache.hadoop.security.UserGroupInformation;
import org.embulk.config.ConfigException;
import org.embulk.output.hdfs.HdfsFileOutputPlugin;
import org.embulk.spi.DataException;
import org.embulk.spi.Exec;
import org.embulk.spi.util.RetryExecutor;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.List;
import java.util.Map;

public class HdfsClient
{
    public static HdfsClient build(HdfsFileOutputPlugin.PluginTask task)
    {
        Configuration conf = buildConfiguration(task.getConfigFiles(), task.getConfig());
        setKerberosKeytabAuthention(conf, task.getKeytabConfig());
        return new HdfsClient(conf, task.getDoas());
    }

    ;

    /**
     * https://docs.cloudera.com/documentation/enterprise/6/6.2/topics/cdh_sg_princ_auth_java.html
     */
    public static void setKerberosKeytabAuthention(Configuration conf, Map<String, String> keytabConfig){
        if(keytabConfig == null || keytabConfig.size() == 0){
            UserGroupInformation.setConfiguration(conf);
            return;
        }
        String krb5ConfigPath = keytabConfig.get("krb5_config_path");
        String keytabPrincipal = keytabConfig.get("keytab_principal");
        String keytabPath = keytabConfig.get("keytab_path");

        logger.info("Keytab config init. krb5_config_path: {} , keytab_principal : {}, keytab_path : {}", krb5ConfigPath, keytabPrincipal, keytabPath);

        if(StringUtils.isEmpty(krb5ConfigPath) ||
                StringUtils.isEmpty(keytabPrincipal) ||
                StringUtils.isEmpty(keytabPath)){
            throw new ConfigException(String.format("Keytab config not enough. krb5_config_path: {} , keytab_principal : {}, keytab_path : {}", krb5ConfigPath, keytabPrincipal, keytabPath));
        }

        try {
            System.setProperty("java.security.krb5.conf", krb5ConfigPath);
            UserGroupInformation.setConfiguration(conf);
            UserGroupInformation.loginUserFromKeytab(keytabPrincipal, keytabPath);
        } catch (IOException e) {
            throw new ConfigException(e);
        }

    }

    ;

    public static Configuration buildConfiguration(List<String> configFiles, Map<String, String> configs)
    {
        Configuration c = new Configuration();
        for (String configFile : configFiles) {
            File file = new File(configFile);
            try {
                c.addResource(file.toURI().toURL());
            }
            catch (MalformedURLException e) {
                throw new ConfigException(e);
            }
        }
        for (Map.Entry<String, String> config : configs.entrySet()) {
            c.set(config.getKey(), config.getValue());
        }
        return c;
    }

    private static Logger logger = Exec.getLogger(HdfsClient.class);
    private final Configuration conf;
    private final FileSystem fs;
    private final Optional<String> user;
    private final RetryExecutor re = RetryExecutor.retryExecutor()
            .withRetryLimit(3)
            .withMaxRetryWait(500)             // ms
            .withMaxRetryWait(10 * 60 * 1000); // ms

    private HdfsClient(Configuration conf, Optional<String> user)
    {
        this.conf = conf;
        this.user = user;
        this.fs = getFs(conf, user);
    }

    private abstract static class Retryable<T>
            implements RetryExecutor.Retryable<T>
    {
        @Override
        public boolean isRetryableException(Exception exception)
        {
            return true; // TODO: which Exception is retryable?
        }

        @Override
        public void onRetry(Exception exception, int retryCount, int retryLimit, int retryWait)
                throws RetryExecutor.RetryGiveupException
        {
            String m = String.format(
                    "%s. (Retry: Count: %d, Limit: %d, Wait: %d ms)",
                    exception.getMessage(),
                    retryCount,
                    retryLimit,
                    retryWait);
            logger.warn(m, exception);
        }

        @Override
        public void onGiveup(Exception firstException, Exception lastException)
                throws RetryExecutor.RetryGiveupException
        {
        }
    }

    private <T> T run(Retryable<T> retryable)
    {
        try {
            return re.run(retryable);
        }
        catch (RetryExecutor.RetryGiveupException e) {
            throw new RuntimeException(e);
        }
    }

    private FileSystem getFs(Configuration conf, Optional<String> user)
    {
        if (user.isPresent()) {
            return getFs(conf, user.get());
        }
        else {
            return getFs(conf);
        }
    }

    private FileSystem getFs(final Configuration conf, final String user)
    {
        return run(new Retryable<FileSystem>()
        {
            @Override
            public FileSystem call()
                    throws Exception
            {
                URI uri = FileSystem.getDefaultUri(conf);
                return FileSystem.get(uri, conf, user);
            }
        });
    }

    private FileSystem getFs(final Configuration conf)
    {
        return run(new Retryable<FileSystem>()
        {
            @Override
            public FileSystem call()
                    throws Exception
            {
                return FileSystem.get(conf);
            }
        });
    }

    public FileStatus[] glob(final Path globPath)
    {
        return run(new Retryable<FileStatus[]>()
        {
            @Override
            public FileStatus[] call()
                    throws Exception
            {
                return fs.globStatus(globPath);
            }
        });
    }

    public boolean trash(final Path path)
    {
        return run(new Retryable<Boolean>()
        {
            @Override
            public Boolean call()
                    throws Exception
            {
                return Trash.moveToAppropriateTrash(fs, path, conf);
            }
        });
    }

    public void globFilesAndTrash(final Path globPath)
    {
        for (final FileStatus fileStatus : glob(globPath)) {
            if (fileStatus.isDirectory()) {
                logger.debug("Skip {} because {} is a directory.",
                        fileStatus.getPath(), fileStatus.getPath());
                continue;
            }
            logger.debug("Move To Trash: {}", fileStatus.getPath());
            if (!trash(fileStatus.getPath())) {
                throw new RuntimeException(String.format("Failed to Move To Trash: %s", fileStatus.getPath()));
            }
        }
    }

    public void globAndTrash(final Path globPath)
    {
        for (final FileStatus fileStatus : glob(globPath)) {
            logger.debug("Move To Trash: {}", fileStatus.getPath());
            if (!trash(fileStatus.getPath())) {
                throw new RuntimeException(String.format("Failed to Move To Trash: %s", fileStatus.getPath()));
            }
        }
    }

    public OutputStream create(final Path path, final boolean overwrite)
    {
        return run(new Retryable<OutputStream>()
        {
            @Override
            public OutputStream call()
                    throws Exception
            {
                return fs.create(path, overwrite);
            }
        });
    }

    public boolean mkdirs(String path)
    {
        return mkdirs(new Path(path));
    }

    public boolean mkdirs(final Path path)
    {
        return run(new Retryable<Boolean>()
        {
            @Override
            public Boolean call()
                    throws Exception
            {
                return fs.mkdirs(path);
            }
        });
    }

    public void close()
    {
        run(new Retryable<Void>()
        {
            @Override
            public Void call()
                    throws Exception
            {
                fs.close();
                return null;
            }
        });
    }

    public void renameDirectory(String src, String dst, boolean trashIfExists)
    {
        renameDirectory(new Path(src), new Path(dst), trashIfExists);
    }

    public void renameDirectory(final Path src, final Path dst, final boolean trashIfExists)
    {
        run(new Retryable<Void>()
        {
            @Override
            public Void call()
                    throws Exception
            {
                if (fs.exists(dst)) {
                    if (!trashIfExists) {
                        throw new DataException(String.format("Directory Exists: %s", dst.toString()));
                    }
                    logger.info("Move To Trash: {}", dst);
                    if (!trash(dst)) {
                        throw new IllegalStateException(String.format("Failed to Move To Trash: %s", dst.toString()));
                    }
                }
                FileContext.getFileContext(conf).rename(src, dst, Options.Rename.NONE);
                logger.debug("Rename: {} >>> {}", src, dst);
                return null;
            }
        });
    }
}