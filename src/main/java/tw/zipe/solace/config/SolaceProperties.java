package tw.zipe.solace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "solace")
public class SolaceProperties {

    private Jms jms = new Jms();
    private Pool pool = new Pool();
    private Received received = new Received();
    private Defaults defaults = new Defaults();

    public Jms getJms() {
        return jms;
    }

    public void setJms(Jms jms) {
        this.jms = jms;
    }

    public Pool getPool() {
        return pool;
    }

    public void setPool(Pool pool) {
        this.pool = pool;
    }

    public Received getReceived() {
        return received;
    }

    public void setReceived(Received received) {
        this.received = received;
    }

    public Defaults getDefaults() {
        return defaults;
    }

    public void setDefaults(Defaults defaults) {
        this.defaults = defaults;
    }

    public static class Jms {
        private String host;
        private String msgVpn;
        private String clientUsername;
        private String clientPassword;
        private Ssl ssl = new Ssl();

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public String getMsgVpn() {
            return msgVpn;
        }

        public void setMsgVpn(String msgVpn) {
            this.msgVpn = msgVpn;
        }

        public String getClientUsername() {
            return clientUsername;
        }

        public void setClientUsername(String clientUsername) {
            this.clientUsername = clientUsername;
        }

        public String getClientPassword() {
            return clientPassword;
        }

        public void setClientPassword(String clientPassword) {
            this.clientPassword = clientPassword;
        }

        public Ssl getSsl() {
            return ssl;
        }

        public void setSsl(Ssl ssl) {
            this.ssl = ssl;
        }
    }

    public static class Ssl {
        private String trustStore;
        private String trustStorePassword;
        private boolean validateCertificate = true;
        private String keyStore;
        private String keyStorePassword;
        private String keyStoreFormat;

        public String getTrustStore() {
            return trustStore;
        }

        public void setTrustStore(String trustStore) {
            this.trustStore = trustStore;
        }

        public String getTrustStorePassword() {
            return trustStorePassword;
        }

        public void setTrustStorePassword(String trustStorePassword) {
            this.trustStorePassword = trustStorePassword;
        }

        public boolean isValidateCertificate() {
            return validateCertificate;
        }

        public void setValidateCertificate(boolean validateCertificate) {
            this.validateCertificate = validateCertificate;
        }

        public String getKeyStore() {
            return keyStore;
        }

        public void setKeyStore(String keyStore) {
            this.keyStore = keyStore;
        }

        public String getKeyStorePassword() {
            return keyStorePassword;
        }

        public void setKeyStorePassword(String keyStorePassword) {
            this.keyStorePassword = keyStorePassword;
        }

        public String getKeyStoreFormat() {
            return keyStoreFormat;
        }

        public void setKeyStoreFormat(String keyStoreFormat) {
            this.keyStoreFormat = keyStoreFormat;
        }
    }

    public static class Pool {
        private int maxTotal = 10;
        private int minIdle = 2;
        private long maxWaitMillis = 5000;
        private boolean testWhileIdle = true;
        private long timeBetweenEvictionRunsMillis = 60000;
        private boolean testOnBorrow = true;

        public int getMaxTotal() {
            return maxTotal;
        }

        public void setMaxTotal(int maxTotal) {
            this.maxTotal = maxTotal;
        }

        public int getMinIdle() {
            return minIdle;
        }

        public void setMinIdle(int minIdle) {
            this.minIdle = minIdle;
        }

        public long getMaxWaitMillis() {
            return maxWaitMillis;
        }

        public void setMaxWaitMillis(long maxWaitMillis) {
            this.maxWaitMillis = maxWaitMillis;
        }

        public boolean isTestWhileIdle() {
            return testWhileIdle;
        }

        public void setTestWhileIdle(boolean testWhileIdle) {
            this.testWhileIdle = testWhileIdle;
        }

        public long getTimeBetweenEvictionRunsMillis() {
            return timeBetweenEvictionRunsMillis;
        }

        public void setTimeBetweenEvictionRunsMillis(long timeBetweenEvictionRunsMillis) {
            this.timeBetweenEvictionRunsMillis = timeBetweenEvictionRunsMillis;
        }

        public boolean isTestOnBorrow() {
            return testOnBorrow;
        }

        public void setTestOnBorrow(boolean testOnBorrow) {
            this.testOnBorrow = testOnBorrow;
        }
    }

    public static class Received {
        private Files files = new Files();

        public Files getFiles() {
            return files;
        }

        public void setFiles(Files files) {
            this.files = files;
        }
    }

    public static class Files {
        private String directory = "received_files";

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }
    }

    public static class Defaults {
        private String topic = "default/topic";
        private String queue = "default-queue";

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getQueue() {
            return queue;
        }

        public void setQueue(String queue) {
            this.queue = queue;
        }
    }
}
