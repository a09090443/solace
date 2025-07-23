package tw.zipe.solace.service;

import com.solacesystems.jcsmp.*;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SolaceSessionFactory extends BasePooledObjectFactory<JCSMPSession> {

    private static final Logger logger = LoggerFactory.getLogger(SolaceSessionFactory.class);
    private final JCSMPProperties properties;

    public SolaceSessionFactory(JCSMPProperties properties) {
        this.properties = properties;
    }

    @Override
    public JCSMPSession create() throws JCSMPException {
        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(properties);
        session.connect();
        logger.debug("創建新的 JCSMP Session");
        return session;
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
