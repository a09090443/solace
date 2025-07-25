package tw.zipe.solace.service;

import com.solacesystems.jcsmp.JCSMPSession;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import tw.zipe.solace.config.SolaceProperties;

import java.time.Duration;

public class SolaceConnectionPool {

    private final GenericObjectPool<JCSMPSession> pool;

    public SolaceConnectionPool(SolaceSessionFactory factory, SolaceProperties.Pool poolConfig) {
        GenericObjectPoolConfig<JCSMPSession> config = new GenericObjectPoolConfig<>();
        config.setMaxTotal(poolConfig.getMaxTotal());
        config.setMinIdle(poolConfig.getMinIdle());
        config.setMaxWait(Duration.ofMillis(poolConfig.getMaxWaitMillis()));
        config.setTestWhileIdle(poolConfig.isTestWhileIdle());
        config.setTimeBetweenEvictionRuns(Duration.ofMillis(poolConfig.getTimeBetweenEvictionRunsMillis()));
        this.pool = new GenericObjectPool<>(factory, config);
        this.pool.setTestOnBorrow(poolConfig.isTestOnBorrow());
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
