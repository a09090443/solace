package tw.zipe.solace.service;

import com.solacesystems.jcsmp.*;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tw.zipe.solace.config.SolaceProperties;

public class SolaceSessionFactory extends BasePooledObjectFactory<JCSMPSession> {

    private static final Logger logger = LoggerFactory.getLogger(SolaceSessionFactory.class);
    private final SolaceProperties solaceProperties;

    public SolaceSessionFactory(SolaceProperties solaceProperties) {
        this.solaceProperties = solaceProperties;
    }

    @Override
    public JCSMPSession create() throws JCSMPException {
        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(createJCSMPProperties());
        session.connect();
        logger.debug("創建新的 JCSMP Session");
        return session;
    }

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

    private String normalizeConnectionHost(String host) {
        if (host != null && (host.startsWith("smf://") || host.startsWith("smfs://"))) {
            return host.replace("smf://", "tcp://").replace("smfs://", "tcps://");
        }
        return host;
    }

    private void setBasicProperties(JCSMPProperties properties, String connectionHost) {
        properties.setProperty(JCSMPProperties.HOST, connectionHost);
        properties.setProperty(JCSMPProperties.USERNAME, solaceProperties.getJms().getClientUsername());
        properties.setProperty(JCSMPProperties.VPN_NAME, solaceProperties.getJms().getMsgVpn());
    }

    private boolean isSecureConnection(String connectionHost) {
        String lowerCaseHost = (connectionHost != null) ? connectionHost.toLowerCase() : "";
        return lowerCaseHost.contains("tcps://");
    }

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

    private boolean shouldUseClientCertificateAuth() {
        String keyStore = solaceProperties.getJms().getSsl().getKeyStore();
        return keyStore != null && !keyStore.isEmpty();
    }

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

    @Override
    public PooledObject<JCSMPSession> wrap(JCSMPSession session) {
        return new DefaultPooledObject<>(session);
    }

    @Override
    public void destroyObject(PooledObject<JCSMPSession> p) throws Exception {
        JCSMPSession session = p.getObject();
        if (session != null && !session.isClosed()) {
            session.closeSession();
            logger.debug("銷毀 JCSMP Session");
        }
        super.destroyObject(p);
    }

    @Override
    public boolean validateObject(PooledObject<JCSMPSession> p) {
        JCSMPSession session = p.getObject();
        boolean isValid = session != null && !session.isClosed();
        if (!isValid) {
            logger.debug("Session 驗證失敗，session 已關閉或為 null");
        }
        return isValid;
    }

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
