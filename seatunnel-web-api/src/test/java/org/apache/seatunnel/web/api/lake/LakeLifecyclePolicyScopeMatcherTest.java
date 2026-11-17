package org.apache.seatunnel.web.api.lake;

import org.apache.seatunnel.web.dao.entity.BusinessSystem;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.DataSourceUnit;
import org.apache.seatunnel.web.dao.entity.LakeLifecyclePolicy;
import org.apache.seatunnel.web.dao.repository.BusinessSystemDao;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.DataSourceUnitDao;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LakeLifecyclePolicyScopeMatcherTest {
    @Test
    void globalPoliciesRemainApplicableAndEverySelectedDimensionMustMatch() {
        var sources = mock(DataSourceDao.class);
        var systems = mock(BusinessSystemDao.class);
        var units = mock(DataSourceUnitDao.class);
        var matcher = new LakeLifecyclePolicyScopeMatcher(sources, systems, units);
        var source = new DataSource();
        source.setId(11L);
        source.setDbType(DbType.MYSQL);
        source.setBusinessSystemId(21L);
        var system = new BusinessSystem();
        system.setId(21L);
        system.setUnitId(31L);
        when(systems.queryById(21L)).thenReturn(system);
        var policy = new LakeLifecyclePolicy();
        assertTrue(matcher.matches(policy, source));
        policy.setDataSourceType("MYSQL");
        policy.setUnitId(31L);
        policy.setBusinessSystemId(21L);
        policy.setSourceDataSourceId(11L);
        assertTrue(matcher.matches(policy, source));
        policy.setDataSourceType("ORACLE");
        assertFalse(matcher.matches(policy, source));
        policy.setDataSourceType("MYSQL");
        policy.setUnitId(32L);
        assertFalse(matcher.matches(policy, source));
        when(units.queryById(32L)).thenReturn(new DataSourceUnit());
        assertThrows(LakeServiceException.class, () -> matcher.validate(policy));
    }

    @Test
    void aSourceSpecificPolicyCannotBeUsedForAnotherSource() {
        var matcher = new LakeLifecyclePolicyScopeMatcher(mock(DataSourceDao.class), mock(BusinessSystemDao.class), mock(DataSourceUnitDao.class));
        var policy = new LakeLifecyclePolicy();
        policy.setSourceDataSourceId(11L);
        var source = new DataSource();
        source.setId(12L);
        assertFalse(matcher.matches(policy, source));
        assertTrue(LakeLifecyclePolicyScopeMatcher.specificity(policy) > LakeLifecyclePolicyScopeMatcher.specificity(new LakeLifecyclePolicy()));
    }
}
