package org.apache.seatunnel.web.api.lake;

import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.LakeLifecyclePolicy;
import org.apache.seatunnel.web.dao.repository.BusinessSystemDao;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.DataSourceUnitDao;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Match against canonical ownership; legacy free-text unit labels are not scope identifiers. */
@Component
public class LakeLifecyclePolicyScopeMatcher {
    private final DataSourceDao sources;
    private final BusinessSystemDao systems;
    private final DataSourceUnitDao units;

    public LakeLifecyclePolicyScopeMatcher(DataSourceDao sources, BusinessSystemDao systems, DataSourceUnitDao units) {
        this.sources = sources;
        this.systems = systems;
        this.units = units;
    }

    public static boolean isScoped(LakeLifecyclePolicy policy) {
        return policy.getSourceDataSourceId() != null || policy.getDataSourceType() != null
                || policy.getUnitId() != null || policy.getBusinessSystemId() != null;
    }

    public DataSource requireSource(Long id) {
        DataSource source = id == null ? null : sources.queryById(id);
        if (source == null) throw invalid("数据源不存在，请重新选择");
        return source;
    }

    public boolean matches(LakeLifecyclePolicy policy, Long sourceId) {
        return !isScoped(policy) || matches(policy, requireSource(sourceId));
    }

    public boolean matches(LakeLifecyclePolicy policy, DataSource source) {
        if (policy.getSourceDataSourceId() != null && !Objects.equals(policy.getSourceDataSourceId(), source.getId())) return false;
        if (policy.getDataSourceType() != null && (source.getDbType() == null
                || !policy.getDataSourceType().equals(source.getDbType().getCode()))) return false;
        if (policy.getBusinessSystemId() != null && !Objects.equals(policy.getBusinessSystemId(), source.getBusinessSystemId())) return false;
        if (policy.getUnitId() != null) {
            var system = source.getBusinessSystemId() == null ? null : systems.queryById(source.getBusinessSystemId());
            if (system == null || !Objects.equals(policy.getUnitId(), system.getUnitId())) return false;
        }
        return true;
    }

    public void validate(LakeLifecyclePolicy policy) {
        if (policy.getDataSourceType() != null) {
            try { policy.setDataSourceType(DbType.valueOf(policy.getDataSourceType()).getCode()); }
            catch (IllegalArgumentException e) { throw invalid("数据类型无效，请从已有类型中选择"); }
        }
        if (policy.getUnitId() != null && units.queryById(policy.getUnitId()) == null) throw invalid("单位不存在");
        if (policy.getBusinessSystemId() != null) {
            var system = systems.queryById(policy.getBusinessSystemId());
            if (system == null) throw invalid("业务系统不存在");
            if (policy.getUnitId() != null && !Objects.equals(policy.getUnitId(), system.getUnitId())) throw invalid("业务系统不属于所选单位");
        }
        if (policy.getSourceDataSourceId() != null && !matches(policy, requireSource(policy.getSourceDataSourceId()))) {
            throw invalid("所选数据源与数据类型、单位或业务系统范围不一致");
        }
    }

    public String describe(LakeLifecyclePolicy policy) {
        var parts = new java.util.ArrayList<String>();
        if (policy.getDataSourceType() != null) parts.add(policy.getDataSourceType());
        if (policy.getUnitId() != null) {
            var unit = units.queryById(policy.getUnitId());
            parts.add(unit == null ? "单位已删除" : unit.getUnitName());
        }
        if (policy.getBusinessSystemId() != null) {
            var system = systems.queryById(policy.getBusinessSystemId());
            parts.add(system == null ? "业务系统已删除" : system.getSystemName());
        }
        if (policy.getSourceDataSourceId() != null) {
            var source = sources.queryById(policy.getSourceDataSourceId());
            parts.add(source == null ? "数据源已删除" : source.getName());
        }
        return parts.isEmpty() ? "全部数据源" : String.join(" / ", parts);
    }

    /** More specific choices appear first, without silently enabling retention. */
    public static int specificity(LakeLifecyclePolicy policy) {
        return (policy.getSourceDataSourceId() == null ? 0 : 8)
                + (policy.getBusinessSystemId() == null ? 0 : 4)
                + (policy.getUnitId() == null ? 0 : 2)
                + (policy.getDataSourceType() == null ? 0 : 1);
    }

    private static LakeServiceException invalid(String message) {
        return new LakeServiceException(LakeErrorCode.LAKE_REQUEST_INVALID, message);
    }
}
