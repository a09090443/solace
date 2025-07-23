package tw.zipe.solace.service;

import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

public class SolaceConnectionPool {

    private final GenericObjectPool<JCSMPSession> pool;

    public SolaceConnectionPool(JCSMPProperties properties) {
        GenericObjectPoolConfig<JCSMPSession> config = new GenericObjectPoolConfig<>();
        config.setMaxTotal(10); // 最大連線數
        config.setMinIdle(2);   // 最小閒置連線數
        config.setMaxWaitMillis(5000); // 最大等待時間
        config.setTestWhileIdle(true);
        config.setTimeBetweenEvictionRunsMillis(60000); // 每分鐘檢查一次
        this.pool = new GenericObjectPool<>(new SolaceSessionFactory(properties), config);
        this.pool.setTestOnBorrow(true);
    }

    public JCSMPSession getSession() throws Exception {
        return pool.borrowObject();
    }

    public void returnSession(JCSMPSession session) {
        if (session != null) {
            pool.returnObject(session);
        }
    }

    public void invalidateSession(JCSMPSession session) {
        if (session != null) {
            try {
                pool.invalidateObject(session);
            } catch (Exception e) {
                // ignore
            }
        }
    }

    public void close() {
        pool.close();
    }
}
