package tw.zipe.solace.service;

import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;

public class SolaceSessionFactory extends BasePooledObjectFactory<JCSMPSession> {

    private final JCSMPProperties properties;

    public SolaceSessionFactory(JCSMPProperties properties) {
        this.properties = properties;
    }

    @Override
    public JCSMPSession create() throws JCSMPException {
        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(properties);
        session.connect();
        return session;
    }

    @Override
    public PooledObject<JCSMPSession> wrap(JCSMPSession session) {
        return new DefaultPooledObject<>(session);
    }

    @Override
    public void destroyObject(PooledObject<JCSMPSession> p) {
        if (p.getObject() != null && !p.getObject().isClosed()) {
            p.getObject().closeSession();
        }
    }

    @Override
    public boolean validateObject(PooledObject<JCSMPSession> p) {
        return p.getObject() != null && !p.getObject().isClosed();
    }
}
