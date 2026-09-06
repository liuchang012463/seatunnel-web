package org.apache.seatunnel.web.api.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeExternalCatalogBinding;
import org.apache.seatunnel.web.dao.repository.LakeExternalCatalogBindingDao;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogPageDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogUpdateDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.LakeExternalCatalogVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LakeExternalCatalogBindingPersistenceServiceTest {

    private static final String CHECKSUM =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void createPendingEnforcesSourceAndTargetReservationsAndStoresNoSecret() {
        LakeExternalCatalogBindingDao dao = mock(LakeExternalCatalogBindingDao.class);
        when(dao.queryBySourceDataSourceIdIncludingDeleted(17L)).thenReturn(null);
        when(dao.queryByLakeDataSourceIdAndCatalogNameIncludingDeleted(99L, "orders_catalog"))
                .thenReturn(null);
        when(dao.insert(any(LakeExternalCatalogBinding.class))).thenAnswer(invocation -> 1);
        LakeExternalCatalogCreateDTO request = createRequest();
        request.setDesiredSpecJson("{\"jdbc_url\":\"jdbc:mysql://db/app\","
                + "\"password\":\"do-not-store\",\"options\":{\"token\":\"bad\"}}");
        request.setCredentialRevision("credential-v7");
        request.setDriverChecksum(CHECKSUM);

        LakeExternalCatalogVO result = new LakeExternalCatalogBindingPersistenceService(dao)
                .createPending(request, 42);

        assertEquals("orders_catalog", result.getTargetCatalogName());
        assertEquals(LakeResourceStatus.PENDING_CREATE, result.getResourceStatus());
        assertEquals(1, result.getGeneration());
        assertEquals(1, result.getLockVersion());
        assertEquals("credential-v7", result.getCredentialRevision());
        assertEquals(CHECKSUM, result.getDriverChecksum());
        assertFalse(result.getActualSnapshot().containsKey("password"));
        verify(dao).insert(any(LakeExternalCatalogBinding.class));

        LakeExternalCatalogBinding active = new LakeExternalCatalogBinding();
        active.setId(1L);
        active.setSourceDataSourceId(17L);
        active.setDeleted(false);
        when(dao.queryBySourceDataSourceIdIncludingDeleted(17L)).thenReturn(active);
        var exception = assertThrows(
                org.apache.seatunnel.web.api.lake.LakeServiceException.class,
                () -> new LakeExternalCatalogBindingPersistenceService(dao)
                        .createPending(createRequest(), 42));
        assertEquals(LakeErrorCode.LAKE_CATALOG_CONFLICT, exception.getLakeErrorCode());
    }

    @Test
    void deletedSourceRowIsReopenedWithNextGenerationUsingVersionCas() {
        LakeExternalCatalogBindingDao dao = mock(LakeExternalCatalogBindingDao.class);
        LakeExternalCatalogBinding tombstone = binding(17L, "old_catalog");
        tombstone.setId(77L);
        tombstone.setDeleted(true);
        tombstone.setGeneration(4);
        tombstone.setLockVersion(8);
        when(dao.queryBySourceDataSourceIdIncludingDeleted(17L)).thenReturn(tombstone);
        when(dao.queryByLakeDataSourceIdAndCatalogNameIncludingDeleted(99L, "new_catalog"))
                .thenReturn(null);
        when(dao.updateIfTokenAndVersionIncludingDeleted(
                any(LakeExternalCatalogBinding.class), eq(null), eq(8)))
                .thenAnswer(invocation -> {
                    LakeExternalCatalogBinding value = invocation.getArgument(0);
                    value.setLockVersion(9);
                    return true;
                });

        LakeExternalCatalogCreateDTO request = createRequest();
        request.setTargetCatalogName("New_Catalog");
        LakeExternalCatalogVO result = new LakeExternalCatalogBindingPersistenceService(dao)
                .createPending(request, 42);

        assertEquals(77L, result.getId());
        assertEquals(5, result.getGeneration());
        assertEquals(9, result.getLockVersion());
        assertFalse(result.getDeleted());
        assertEquals(LakeResourceStatus.PENDING_CREATE, result.getResourceStatus());
        verify(dao).updateIfTokenAndVersionIncludingDeleted(
                any(LakeExternalCatalogBinding.class), eq(null), eq(8));
    }

    @Test
    void updatePendingUsesOptimisticVersionAndNeverReturnsDesiredJson() {
        LakeExternalCatalogBindingDao dao = mock(LakeExternalCatalogBindingDao.class);
        LakeExternalCatalogBinding current = binding(17L, "orders_catalog");
        current.setId(77L);
        current.setLockVersion(3);
        when(dao.queryActiveById(77L)).thenReturn(current);
        when(dao.queryByLakeDataSourceIdAndCatalogNameIncludingDeleted(99L, "new_catalog"))
                .thenReturn(null);
        when(dao.updateIfTokenAndVersion(any(LakeExternalCatalogBinding.class), eq(null), eq(3)))
                .thenAnswer(invocation -> {
                    LakeExternalCatalogBinding value = invocation.getArgument(0);
                    value.setLockVersion(4);
                    return true;
                });
        LakeExternalCatalogUpdateDTO request = new LakeExternalCatalogUpdateDTO();
        request.setTargetCatalogName("New_Catalog");
        request.setAdapter("MYSQL");
        request.setScope(LakeCatalogScope.ALL);
        request.setExpectedLockVersion(3);
        request.setDesiredSpecJson("{\"jdbc_url\":\"jdbc:mysql://db/app\","
                + "\"password\":\"not-returned\"}");

        LakeExternalCatalogVO result = new LakeExternalCatalogBindingPersistenceService(dao)
                .updatePending(77L, request, 42);

        assertEquals("new_catalog", result.getTargetCatalogName());
        assertEquals(LakeResourceStatus.CREATING, result.getResourceStatus());
        assertEquals(4, result.getLockVersion());
        assertFalse(result.toString().contains("jdbc:mysql://db/app"));
        verify(dao).updateIfTokenAndVersion(any(LakeExternalCatalogBinding.class), eq(null), eq(3));
    }

    @Test
    void finalizeSuccessAndFailureMaskSnapshotErrorsAndUseTokenCas() {
        LakeExternalCatalogBindingDao dao = mock(LakeExternalCatalogBindingDao.class);
        LakeExternalCatalogBinding current = binding(17L, "orders_catalog");
        current.setId(77L);
        current.setLockVersion(5);
        current.setOperationToken("operation-token");
        when(dao.queryByIdIncludingDeleted(77L)).thenReturn(current);
        when(dao.updateIfTokenAndVersionIncludingDeleted(
                any(LakeExternalCatalogBinding.class), eq("operation-token"), eq(5)))
                .thenAnswer(invocation -> {
                    LakeExternalCatalogBinding value = invocation.getArgument(0);
                    value.setLockVersion(6);
                    return true;
                });
        LakeExternalCatalogBindingPersistenceService service =
                new LakeExternalCatalogBindingPersistenceService(dao);

        LakeExternalCatalogVO success = service.finalizeSuccess(
                77L, "operation-token", 5,
                "{\"type\":\"jdbc\",\"jdbc_url\":\"jdbc:mysql://db/app\","
                        + "\"password\":\"secret\"}", "MATCH");
        assertEquals(LakeResourceStatus.READY, success.getResourceStatus());
        assertTrue(success.getActualSnapshot().containsKey("jdbc_url"));
        assertEquals("******", success.getActualSnapshot().get("jdbc_url"));
        assertFalse(success.toString().contains("jdbc:mysql://db/app"));
        assertFalse(success.toString().contains("secret"));

        current.setLockVersion(6);
        current.setOperationToken("operation-token-2");
        when(dao.updateIfTokenAndVersionIncludingDeleted(
                any(LakeExternalCatalogBinding.class), eq("operation-token-2"), eq(6)))
                .thenReturn(true);
        LakeExternalCatalogVO failure = service.finalizeFailure(
                77L, "operation-token-2", 6, "UPSTREAM_FAILURE",
                "SQLException jdbc:mysql://db/app password=secret", null, "UNKNOWN");
        assertEquals(LakeResourceStatus.ERROR, failure.getResourceStatus());
        assertEquals(7, failure.getLockVersion());
        assertEquals("[REDACTED_ERROR]", failure.getErrorMessage());
        assertFalse(failure.toString().contains("jdbc:mysql://db/app"));
        assertFalse(failure.toString().contains("secret"));
    }

    @Test
    void lowLevelPendingClaimUsesPreviousTokenAndPublishesNextVersion() {
        LakeExternalCatalogBindingDao dao = mock(LakeExternalCatalogBindingDao.class);
        LakeExternalCatalogBinding entity = binding(17L, "orders_catalog");
        entity.setId(77L);
        entity.setLockVersion(3);
        entity.setOperationToken(null);
        when(dao.updateIfTokenAndVersion(
                any(LakeExternalCatalogBinding.class), eq(null), eq(3))).thenReturn(true);

        boolean updated = new LakeExternalCatalogBindingPersistenceService(dao)
                .updatePending(entity, "operation-token", 3);

        assertTrue(updated);
        assertEquals("operation-token", entity.getOperationToken());
        assertEquals(4, entity.getLockVersion());
        verify(dao).updateIfTokenAndVersion(
                any(LakeExternalCatalogBinding.class), eq(null), eq(3));
    }

    @Test
    void pageAndDetailAreLocalOnlyAndExposeNoOperationTokenOrDesiredJson() {
        LakeExternalCatalogBindingDao dao = mock(LakeExternalCatalogBindingDao.class);
        LakeExternalCatalogBinding row = binding(17L, "orders_catalog");
        row.setId(77L);
        row.setOperationToken("must-not-return");
        row.setDesiredSpecJson("{\"jdbc_url\":\"jdbc:mysql://db/app\"}");
        Page<LakeExternalCatalogBinding> page = new Page<>(1, 10);
        page.setRecords(List.of(row));
        page.setTotal(1);
        when(dao.queryActivePage(any(Page.class), eq(null), eq(null), eq("orders"),
                eq(null), eq(null), eq(null))).thenReturn(page);
        when(dao.queryByIdIncludingDeleted(77L)).thenReturn(row);

        LakeExternalCatalogBindingPersistenceService service =
                new LakeExternalCatalogBindingPersistenceService(dao);
        LakeExternalCatalogPageDTO request = new LakeExternalCatalogPageDTO();
        request.setTargetCatalogName("orders");
        PaginationResult<LakeExternalCatalogVO> result = service.page(request);
        LakeExternalCatalogVO detail = service.detail(77L);

        assertEquals(1, result.getData().getBizData().size());
        assertNotNull(detail);
        assertFalse(detail.toString().contains("must-not-return"));
        assertFalse(detail.toString().contains("jdbc:mysql://db/app"));
        verify(dao).queryActivePage(any(Page.class), eq(null), eq(null), eq("orders"),
                eq(null), eq(null), eq(null));
    }

    private static LakeExternalCatalogCreateDTO createRequest() {
        LakeExternalCatalogCreateDTO request = new LakeExternalCatalogCreateDTO();
        request.setLakeDataSourceId(99L);
        request.setSourceDataSourceId(17L);
        request.setTargetCatalogName("Orders_Catalog");
        request.setAdapter("MYSQL");
        request.setScope(LakeCatalogScope.ALL);
        request.setOptions(Map.of("lower_case_meta_names", "false"));
        return request;
    }

    private static LakeExternalCatalogBinding binding(Long sourceId, String catalogName) {
        LakeExternalCatalogBinding binding = new LakeExternalCatalogBinding();
        binding.setLakeDataSourceId(99L);
        binding.setSourceDataSourceId(sourceId);
        binding.setTargetCatalogName(catalogName);
        binding.setAdapter("MYSQL");
        binding.setScope(LakeCatalogScope.ALL);
        binding.setDesiredSpecJson("{}");
        binding.setDesiredSpecHash(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        binding.setLockVersion(1);
        binding.setGeneration(1);
        binding.setDeleted(false);
        binding.setResourceStatus(LakeResourceStatus.READY);
        return binding;
    }
}
