package org.apache.seatunnel.web.api.service.impl.client;

import org.apache.seatunnel.web.dao.entity.SeaTunnelClient;
import org.apache.seatunnel.web.dao.repository.SeaTunnelClientDao;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SeaTunnelClientQueryAppServiceTest {

    @Test
    void exposesOnlyTheSelectedClientEngineVersion() {
        SeaTunnelClient client = new SeaTunnelClient();
        client.setClientVersion("3.0.0");
        SeaTunnelClientDao dao = (SeaTunnelClientDao) Proxy.newProxyInstance(
                SeaTunnelClientDao.class.getClassLoader(),
                new Class<?>[] {SeaTunnelClientDao.class},
                (proxy, method, args) -> "queryById".equals(method.getName()) ? client : null);

        SeaTunnelClientQueryAppService service = new SeaTunnelClientQueryAppService();
        ReflectionTestUtils.setField(service, "seaTunnelClientDao", dao);

        assertEquals("3.0.0", service.version(42L));
    }
}
