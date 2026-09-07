package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.dao.entity.LakeWarehouseConfig;
import org.apache.seatunnel.web.spi.bean.vo.LakeDorisHardwareVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LakeWarehouseServiceImplTest {

    @Test
    void keepsTheSubmittedPasswordInPlaintextForTheFirstConfiguration() {
        assertEquals("doris-secret",
                LakeWarehouseServiceImpl.resolvePassword(null, "doris-secret"));
    }

    @Test
    void keepsTheExistingPasswordWhenUpdateOmitsIt() {
        LakeWarehouseConfig current = new LakeWarehouseConfig();
        current.setPassword("existing-password");

        assertEquals("existing-password",
                LakeWarehouseServiceImpl.resolvePassword(current, "  "));
        assertNull(LakeWarehouseServiceImpl.resolvePassword(null, "  "));
    }

    @Test
    void parsesTheDorisHomeHardwareSummaryIntoDisplaySafeFields() {
        String payload = """
                {"code":0,"data":{"VersionInfo":{"Version":"doris-4.1.2-rc01","BuildInfo":"qa","BuildTime":"2026-06-12"},"HardwareInfo":{"NetworkParameter":"Host name: fe Domain name: fe DNS servers: [127.0.0.11] IPv4 Gateway: 172.26.0.1","Processor":"HUAWEI,Kunpeng 920 2 physical CPU package(s) 96 physical CPU core(s) CPU load: 3.0%","OS":"GNU/Linux Ubuntu 22.04.5 LTS Uptime: 9 days","Memory":"Memory: 107.6 GiB/254.1 GiB Swap used: 16.1 MiB/4.0 GiB","FileSystem":"37.6 TiB of 39.9 TiB free (94.2%)","NetworkInterface":"IPv4 [172.26.0.2] Traffic received: 1.2 GiB, Traffic transmitted: 2.4 GiB","Processes":"Processes: 4, Threads: 22089","Disk":"Disks: /dev/sda"}}}
                """;

        LakeDorisHardwareVO result = LakeWarehouseServiceImpl.parseHardwarePayload(payload);

        assertEquals("doris-4.1.2-rc01", result.getVersion());
        assertEquals("fe", result.getHostName());
        assertEquals("172.26.0.2", result.getIpv4());
        assertEquals("HUAWEI,Kunpeng 920", result.getCpuModel());
        assertEquals(96, result.getCpuCores());
        assertEquals("3.0%", result.getCpuLoad());
        assertEquals("42.3%", result.getMemoryUsedPercent());
        assertEquals("94.2%", result.getFilesystemFreePercent());
        assertEquals(4, result.getProcessCount());
        assertEquals(22089, result.getThreadCount());
    }
}
