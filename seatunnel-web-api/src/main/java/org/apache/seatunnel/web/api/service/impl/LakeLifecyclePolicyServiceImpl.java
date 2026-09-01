package org.apache.seatunnel.web.api.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.service.LakeLifecyclePolicyService;
import org.apache.seatunnel.web.common.enums.LakeLifecyclePolicyStatus;
import org.apache.seatunnel.web.dao.entity.LakeLifecyclePolicy;
import org.apache.seatunnel.web.dao.repository.LakeLifecyclePolicyDao;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyDisableDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyPageDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyUpdateDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.LakeLifecyclePolicyVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/** Local CRUD and optimistic state machine for reusable lifecycle policies. */
@Service
public class LakeLifecyclePolicyServiceImpl implements LakeLifecyclePolicyService {

    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 1000;
    private static final int MAX_POLICY_NAME_LENGTH = 128;
    private static final int MAX_DESCRIPTION_LENGTH = 1024;

    private final LakeLifecyclePolicyDao policyDao;
    private final CurrentUserProvider currentUserProvider;

    @Autowired
    public LakeLifecyclePolicyServiceImpl(
            LakeLifecyclePolicyDao policyDao, CurrentUserProvider currentUserProvider) {
        this.policyDao = Objects.requireNonNull(policyDao, "policyDao");
        this.currentUserProvider = Objects.requireNonNull(currentUserProvider, "currentUserProvider");
    }

    @Override
    public PaginationResult<LakeLifecyclePolicyVO> page(LakeLifecyclePolicyPageDTO request) {
        LakeLifecyclePolicyPageDTO safe = request == null ? new LakeLifecyclePolicyPageDTO() : request;
        validatePage(safe);
        IPage<LakeLifecyclePolicy> page;
        try {
            page = policyDao.queryPage(safe);
        } catch (RuntimeException exception) {
            throw conflict("Lifecycle policies cannot be read");
        }
        if (page == null) {
            throw conflict("Lifecycle policies cannot be read");
        }
        List<LakeLifecyclePolicyVO> records = page.getRecords() == null
                ? List.of()
                : page.getRecords().stream().map(this::toVO).toList();
        return PaginationResult.buildSuc(records, page);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LakeLifecyclePolicyVO create(LakeLifecyclePolicyCreateDTO request) {
        validateCreate(request);
        String policyName = request.getPolicyName().trim();
        try {
            if (policyDao.queryByPolicyName(policyName) != null) {
                throw conflict("Lifecycle policy name already exists");
            }
            LakeLifecyclePolicy entity = new LakeLifecyclePolicy();
            entity.initInsert();
            entity.setPolicyName(policyName);
            entity.setVersion(1);
            entity.setStatus(normalizeCreateStatus(request.getStatus()));
            entity.setGranularity(request.getGranularity());
            entity.setRetentionCount(request.getRetentionCount());
            entity.setDescription(normalizeDescription(request.getDescription()));
            Integer userId = currentUserId();
            entity.setCreateUserId(userId);
            entity.setUpdateUserId(userId);
            if (policyDao.insert(entity) <= 0) {
                throw conflict("Lifecycle policy cannot be created");
            }
            return toVO(entity);
        } catch (DuplicateKeyException exception) {
            throw conflict("Lifecycle policy name already exists");
        } catch (LakeServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw conflict("Lifecycle policy cannot be created");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LakeLifecyclePolicyVO update(Long id, LakeLifecyclePolicyUpdateDTO request) {
        validateId(id);
        validateUpdate(request);
        LakeLifecyclePolicy existing = findById(id);
        if (existing.getStatus() == LakeLifecyclePolicyStatus.DISABLED) {
            throw conflict("Disabled lifecycle policy cannot be updated");
        }
        requireExpectedVersion(existing, request.getExpectedVersion());

        String policyName = request.getPolicyName().trim();
        LakeLifecyclePolicy desired = copyForUpdate(existing);
        desired.setPolicyName(policyName);
        desired.setStatus(request.getStatus());
        desired.setGranularity(request.getGranularity());
        desired.setRetentionCount(request.getRetentionCount());
        desired.setDescription(normalizeDescription(request.getDescription()));
        if (sameDefinition(existing, desired)) {
            return toVO(existing);
        }

        try {
            if (policyDao.queryByPolicyNameExcludeId(policyName, id) != null) {
                throw conflict("Lifecycle policy name already exists");
            }
            desired.setVersion(existing.getVersion() + 1);
            desired.setUpdateUserId(currentUserId());
            desired.setUpdateTime(new Date());
            if (!policyDao.updateIfVersion(desired, request.getExpectedVersion())) {
                throw conflict("Lifecycle policy version conflict");
            }
            return toVO(desired);
        } catch (DuplicateKeyException exception) {
            throw conflict("Lifecycle policy name already exists");
        } catch (LakeServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw conflict("Lifecycle policy cannot be updated");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LakeLifecyclePolicyVO disable(Long id, LakeLifecyclePolicyDisableDTO request) {
        validateId(id);
        validateDisable(request);
        LakeLifecyclePolicy existing = findById(id);
        if (existing.getStatus() == LakeLifecyclePolicyStatus.DISABLED) {
            return toVO(existing);
        }
        requireExpectedVersion(existing, request.getExpectedVersion());

        LakeLifecyclePolicy desired = copyForUpdate(existing);
        desired.setStatus(LakeLifecyclePolicyStatus.DISABLED);
        desired.setVersion(existing.getVersion() + 1);
        desired.setUpdateUserId(currentUserId());
        desired.setUpdateTime(new Date());
        try {
            if (!policyDao.updateIfVersion(desired, request.getExpectedVersion())) {
                throw conflict("Lifecycle policy version conflict");
            }
            return toVO(desired);
        } catch (LakeServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw conflict("Lifecycle policy cannot be disabled");
        }
    }

    private LakeLifecyclePolicy findById(Long id) {
        try {
            LakeLifecyclePolicy entity = policyDao.queryById(id);
            if (entity == null) {
                throw conflict("Lifecycle policy does not exist");
            }
            if (entity.getVersion() == null || entity.getVersion() <= 0) {
                throw conflict("Lifecycle policy has invalid version");
            }
            return entity;
        } catch (LakeServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw conflict("Lifecycle policy cannot be read");
        }
    }

    private void validatePage(LakeLifecyclePolicyPageDTO request) {
        int pageNo = request.getPageNo() == null ? DEFAULT_PAGE_NO : request.getPageNo();
        int pageSize = request.getPageSize() == null ? DEFAULT_PAGE_SIZE : request.getPageSize();
        if (pageNo < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw invalid("pageNo/pageSize");
        }
        request.setPageNo(pageNo);
        request.setPageSize(pageSize);
        if (request.getPolicyName() != null
                && request.getPolicyName().trim().length() > MAX_POLICY_NAME_LENGTH) {
            throw invalid("policyName");
        }
    }

    private void validateCreate(LakeLifecyclePolicyCreateDTO request) {
        if (request == null) {
            throw invalid("policy");
        }
        validateDefinition(request.getPolicyName(), request.getGranularity(),
                request.getRetentionCount(), request.getDescription());
        validateWriteStatus(normalizeCreateStatus(request.getStatus()));
    }

    private void validateUpdate(LakeLifecyclePolicyUpdateDTO request) {
        if (request == null) {
            throw invalid("policy");
        }
        validateDefinition(request.getPolicyName(), request.getGranularity(),
                request.getRetentionCount(), request.getDescription());
        validateWriteStatus(request.getStatus());
        if (request.getExpectedVersion() == null || request.getExpectedVersion() <= 0) {
            throw invalid("expectedVersion");
        }
    }

    private void validateDisable(LakeLifecyclePolicyDisableDTO request) {
        if (request == null || request.getExpectedVersion() == null
                || request.getExpectedVersion() <= 0) {
            throw invalid("expectedVersion");
        }
    }

    private void validateDefinition(
            String policyName, Object granularity, Integer retentionCount, String description) {
        if (StringUtils.isBlank(policyName)
                || policyName.trim().length() > MAX_POLICY_NAME_LENGTH
                || granularity == null
                || retentionCount == null
                || retentionCount <= 0
                || normalizeDescription(description) != null
                        && normalizeDescription(description).length() > MAX_DESCRIPTION_LENGTH) {
            throw invalid("policy definition");
        }
    }

    private void validateWriteStatus(LakeLifecyclePolicyStatus status) {
        if (status == null || status == LakeLifecyclePolicyStatus.DISABLED) {
            throw invalid("status");
        }
    }

    private void requireExpectedVersion(LakeLifecyclePolicy existing, Integer expectedVersion) {
        if (!Objects.equals(existing.getVersion(), expectedVersion)) {
            throw conflict("Lifecycle policy version conflict");
        }
    }

    private LakeLifecyclePolicyStatus normalizeCreateStatus(LakeLifecyclePolicyStatus status) {
        return status == null ? LakeLifecyclePolicyStatus.DRAFT : status;
    }

    private String normalizeDescription(String description) {
        return description == null ? null : StringUtils.trimToNull(description);
    }

    private Integer currentUserId() {
        try {
            Integer userId = currentUserProvider.getCurrentUserId();
            if (userId == null || userId <= 0) {
                throw conflict("Current user is required");
            }
            return userId;
        } catch (LakeServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw conflict("Current user is required");
        }
    }

    private boolean sameDefinition(LakeLifecyclePolicy left, LakeLifecyclePolicy right) {
        return Objects.equals(left.getPolicyName(), right.getPolicyName())
                && Objects.equals(left.getStatus(), right.getStatus())
                && Objects.equals(left.getGranularity(), right.getGranularity())
                && Objects.equals(left.getRetentionCount(), right.getRetentionCount())
                && Objects.equals(left.getDescription(), right.getDescription());
    }

    private LakeLifecyclePolicy copyForUpdate(LakeLifecyclePolicy source) {
        LakeLifecyclePolicy target = new LakeLifecyclePolicy();
        target.setId(source.getId());
        target.setPolicyName(source.getPolicyName());
        target.setVersion(source.getVersion());
        target.setStatus(source.getStatus());
        target.setGranularity(source.getGranularity());
        target.setRetentionCount(source.getRetentionCount());
        target.setDescription(source.getDescription());
        target.setCreateUserId(source.getCreateUserId());
        target.setUpdateUserId(source.getUpdateUserId());
        target.setCreateTime(source.getCreateTime());
        target.setUpdateTime(source.getUpdateTime());
        return target;
    }

    private LakeLifecyclePolicyVO toVO(LakeLifecyclePolicy entity) {
        LakeLifecyclePolicyVO result = new LakeLifecyclePolicyVO();
        result.setId(entity.getId());
        result.setPolicyName(entity.getPolicyName());
        result.setVersion(entity.getVersion());
        result.setStatus(entity.getStatus());
        result.setGranularity(entity.getGranularity());
        result.setRetentionCount(entity.getRetentionCount());
        result.setDescription(entity.getDescription());
        result.setCreateUserId(entity.getCreateUserId());
        result.setUpdateUserId(entity.getUpdateUserId());
        result.setCreateTime(entity.getCreateTime());
        result.setUpdateTime(entity.getUpdateTime());
        return result;
    }

    private void validateId(Long id) {
        if (id == null || id <= 0) {
            throw invalid("id");
        }
    }

    private static LakeServiceException invalid(String field) {
        return new LakeServiceException(LakeErrorCode.LAKE_REQUEST_INVALID,
                "Invalid lifecycle policy " + field);
    }

    private static LakeServiceException conflict(String message) {
        return new LakeServiceException(LakeErrorCode.LAKE_RESOURCE_CONFLICT, message);
    }
}
