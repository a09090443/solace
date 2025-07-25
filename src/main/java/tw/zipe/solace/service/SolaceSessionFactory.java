package tw.zipe.solace.service;

import com.solacesystems.jcsmp.*;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tw.zipe.solace.config.SolaceProperties;

/**
 * {@link JCSMPSession} 的工廠類別，用於 {@link SolaceConnectionPool}。
 * <p>
 * 實現了 Apache Commons Pool2 的 {@link BasePooledObjectFactory}，
 * 負責建立、銷毀、驗證和鈍化 JCSMP Session 物件。
 * 它會根據 {@link SolaceProperties} 中的組態來設定連線屬性，
 * 包括基本連線資訊、SSL/TLS 安全設定以及通道屬性 (如重連策略)。
 * </p>
 */
public class SolaceSessionFactory extends BasePooledObjectFactory<JCSMPSession> {

    private static final Logger logger = LoggerFactory.getLogger(SolaceSessionFactory.class);
    private final SolaceProperties solaceProperties;

    /**
     * 建構一個新的 SolaceSessionFactory。
     *
     * @param solaceProperties Solace 組態屬性，用於設定 JCSMP 連線。
     */
    public SolaceSessionFactory(SolaceProperties solaceProperties) {
        this.solaceProperties = solaceProperties;
    }

    /**
     * 建立一個新的 JCSMP Session 並連線。
     *
     * @return 已連線的 {@link JCSMPSession}。
     * @throws JCSMPException 如果建立或連線過程中發生錯誤。
     */
    @Override
    public JCSMPSession create() throws JCSMPException {
        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(createJCSMPProperties());
        session.connect();
        logger.debug("創建新的 JCSMP Session");
        return session;
    }

    /**
     * 根據組態檔建立 JCSMPProperties 物件。
     *
     * @return 設定好的 {@link JCSMPProperties}。
     */
    public JCSMPProperties createJCSMPProperties() {
        String connectionHost = normalizeConnectionHost(solaceProperties.getJms().getHost());

        final JCSMPProperties properties = new JCSMPProperties();
        setBasicProperties(properties, connectionHost);

        boolean isSecureConnection = isSecureConnection(connectionHost);
        if (isSecureConnection) {
            configureSslProperties(properties);
        } else {
            properties.setProperty(JCSMPProperties.PASSWORD, solaceProperties.getJms().getClientPassword());
        }

        configureChannelProperties(properties);
        return properties;
    }

    /**
     * 將主機 URL 標準化為 `tcp://` 或 `tcps://` 格式。
     */
    private String normalizeConnectionHost(String host) {
        if (host != null && (host.startsWith("smf://") || host.startsWith("smfs://"))) {
            return host.replace("smf://", "tcp://").replace("smfs://", "tcps://");
        }
        return host;
    }

    /**
     * 設定基本連線屬性 (主機、使用者名稱、VPN)。
     */
    private void setBasicProperties(JCSMPProperties properties, String connectionHost) {
        properties.setProperty(JCSMPProperties.HOST, connectionHost);
        properties.setProperty(JCSMPProperties.USERNAME, solaceProperties.getJms().getClientUsername());
        properties.setProperty(JCSMPProperties.VPN_NAME, solaceProperties.getJms().getMsgVpn());
    }

    /**
     * 檢查連線是否為安全連線 (tcps)。
     */
    private boolean isSecureConnection(String connectionHost) {
        String lowerCaseHost = (connectionHost != null) ? connectionHost.toLowerCase() : "";
        return lowerCaseHost.contains("tcps://");
    }

    /**
     * 設定 SSL 相關屬性，包括信任儲存區和客戶端憑證 (如果需要)。
     */
    private void configureSslProperties(JCSMPProperties properties) {
        configureTrustStore(properties);

        boolean useClientCertificateAuth = shouldUseClientCertificateAuth();
        if (useClientCertificateAuth) {
            configureClientCertificate(properties);
        } else {
            properties.setProperty(JCSMPProperties.PASSWORD, solaceProperties.getJms().getClientPassword());
        }

        properties.setBooleanProperty(JCSMPProperties.SSL_VALIDATE_CERTIFICATE,
                solaceProperties.getJms().getSsl().isValidateCertificate());
    }

    /**
     * 設定信任儲存區 (TrustStore)。
     */
    private void configureTrustStore(JCSMPProperties properties) {
        String trustStore = solaceProperties.getJms().getSsl().getTrustStore();
        if (trustStore != null && !trustStore.isEmpty()) {
            properties.setProperty(JCSMPProperties.SSL_TRUST_STORE, trustStore);
            String trustStorePassword = solaceProperties.getJms().getSsl().getTrustStorePassword();
            if (trustStorePassword != null) {
                properties.setProperty(JCSMPProperties.SSL_TRUST_STORE_PASSWORD, trustStorePassword);
            }
        }
    }

    /**
     * 判斷是否應使用客戶端憑證進行驗證。
     */
    private boolean shouldUseClientCertificateAuth() {
        String keyStore = solaceProperties.getJms().getSsl().getKeyStore();
        return keyStore != null && !keyStore.isEmpty();
    }

    /**
     * 設定客戶端憑證 (KeyStore) 相關屬性。
     */
    private void configureClientCertificate(JCSMPProperties properties) {
        properties.setProperty(JCSMPProperties.SSL_KEY_STORE, solaceProperties.getJms().getSsl().getKeyStore());

        String keyStorePassword = solaceProperties.getJms().getSsl().getKeyStorePassword();
        if (keyStorePassword != null) {
            properties.setProperty(JCSMPProperties.SSL_KEY_STORE_PASSWORD, keyStorePassword);
        }

        String keyStoreFormat = solaceProperties.getJms().getSsl().getKeyStoreFormat();
        if (keyStoreFormat != null) {
            properties.setProperty(JCSMPProperties.SSL_KEY_STORE_FORMAT, keyStoreFormat);
        }

        properties.setProperty(JCSMPProperties.AUTHENTICATION_SCHEME,
                JCSMPProperties.AUTHENTICATION_SCHEME_CLIENT_CERTIFICATE);
    }

    /**
     * 設定通道屬性，特別是重連策略。
     */
    private void configureChannelProperties(JCSMPProperties properties) {
        JCSMPChannelProperties cp = (JCSMPChannelProperties) properties
                .getProperty(JCSMPProperties.CLIENT_CHANNEL_PROPERTIES);
        // 啟用無限重連
        cp.setReconnectRetries(-1);
        // 重連間隔 3 秒
        cp.setReconnectRetryWaitInMillis(5000);
        // 自動重新訂閱 Topic
        properties.setBooleanProperty(JCSMPProperties.REAPPLY_SUBSCRIPTIONS, true);
    }

    /**
     * 將一個 JCSMP Session 包裝成 {@link PooledObject}。
     *
     * @param session 要包裝的 Session。
     * @return 包裝後的 PooledObject。
     */
    @Override
    public PooledObject<JCSMPSession> wrap(JCSMPSession session) {
        return new DefaultPooledObject<>(session);
    }

    /**
     * 銷毀一個 Session 物件 (關閉連線)。
     *
     * @param p 要銷毀的 PooledObject。
     * @throws Exception 如果關閉 Session 時發生錯誤。
     */
    @Override
    public void destroyObject(PooledObject<JCSMPSession> p) throws Exception {
        JCSMPSession session = p.getObject();
        if (session != null && !session.isClosed()) {
            session.closeSession();
            logger.debug("銷毀 JCSMP Session");
        }
        super.destroyObject(p);
    }

    /**
     * 驗證一個 Session 物件是否仍然有效 (未關閉)。
     *
     * @param p 要驗證的 PooledObject。
     * @return 如果 Session 有效則返回 true，否則返回 false。
     */
    @Override
    public boolean validateObject(PooledObject<JCSMPSession> p) {
        JCSMPSession session = p.getObject();
        boolean isValid = session != null && !session.isClosed();
        if (!isValid) {
            logger.debug("Session 驗證失敗，session 已關閉或為 null");
        }
        return isValid;
    }

    /**
     * 在將 Session 物件歸還到池之前呼叫，用於進行清理或狀態重設。
     * <p>
     * 此處檢查 Session 是否在歸還前就已關閉，如果是，則拋出異常以防止其被放回池中。
     * </p>
     *
     * @param p 要鈍化的 PooledObject。
     * @throws Exception 如果 Session 已關閉。
     */
    @Override
    public void passivateObject(PooledObject<JCSMPSession> p) throws Exception {
        // 在歸還到池之前進行額外的檢查
        JCSMPSession session = p.getObject();
        if (session != null && session.isClosed()) {
            logger.warn("歸還到池的 session 已關閉，將被標記為無效");
            throw new IllegalStateException("Session is closed");
        }
        super.passivateObject(p);
    }
}
