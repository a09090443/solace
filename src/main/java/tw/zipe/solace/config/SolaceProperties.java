package tw.zipe.solace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Solace 相關的組態屬性類別。
 * <p>
 * 透過 {@code @ConfigurationProperties(prefix = "solace")} 將 application.properties
 * 或 application.yml 中以 "solace" 為前綴的屬性綁定到此類別的欄位中。
 * 包含了 JMS 連線設定、連線池設定、接收檔案的設定以及預設的目的地名稱。
 * </p>
 */
@Configuration
@ConfigurationProperties(prefix = "solace")
public class SolaceProperties {

    private Jms jms = new Jms();
    private Pool pool = new Pool();
    private Received received = new Received();
    private Defaults defaults = new Defaults();

    // Getters and Setters

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

    /**
     * JMS 連線相關的屬性。
     */
    public static class Jms {
        /** Solace 代理的主機位址 (例如: tcp://localhost:55555) */
        private String host;
        /** 訊息 VPN 名稱 */
        private String msgVpn;
        /** 用戶端使用者名稱 */
        private String clientUsername;
        /** 用戶端密碼 */
        private String clientPassword;
        /** SSL/TLS 相關設定 */
        private Ssl ssl = new Ssl();

        // Getters and Setters

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

    /**
     * SSL/TLS 連線相關的屬性。
     */
    public static class Ssl {
        /** 信任儲存區 (TrustStore) 的路徑 */
        private String trustStore;
        /** 信任儲存區的密碼 */
        private String trustStorePassword;
        /** 是否驗證伺服器憑證 */
        private boolean validateCertificate = true;
        /** 金鑰儲存區 (KeyStore) 的路徑，用於客戶端憑證驗證 */
        private String keyStore;
        /** 金鑰儲存區的密碼 */
        private String keyStorePassword;
        /** 金鑰儲存區的格式 (例如: JKS, PKCS12) */
        private String keyStoreFormat;

        // Getters and Setters

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

    /**
     * JCSMP Session 連線池的屬性。
     */
    public static class Pool {
        /** 池中最大連線數 */
        private int maxTotal = 10;
        /** 池中最小空閒連線數 */
        private int minIdle = 2;
        /** 當池中沒有可用連線時，等待的最長時間 (毫秒) */
        private long maxWaitMillis = 5000;
        /** 是否在連線空閒時進行測試 */
        private boolean testWhileIdle = true;
        /** 空閒連線驅逐執行緒的執行間隔 (毫秒) */
        private long timeBetweenEvictionRunsMillis = 60000;
        /** 是否在借用連線時進行測試 */
        private boolean testOnBorrow = true;

        // Getters and Setters

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

    /**
     * 接收到的資料的相關設定。
     */
    public static class Received {
        private Files files = new Files();

        public Files getFiles() {
            return files;
        }

        public void setFiles(Files files) {
            this.files = files;
        }
    }

    /**
     * 接收到的檔案的儲存設定。
     */
    public static class Files {
        /** 儲存接收到的檔案的目錄 */
        private String directory = "received_files";

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }
    }

    /**
     * 預設的目的地名稱設定。
     */
    public static class Defaults {
        /** 預設的 Topic 名稱 */
        private String topic = "default/topic";
        /** 預設的 Queue 名稱 */
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
