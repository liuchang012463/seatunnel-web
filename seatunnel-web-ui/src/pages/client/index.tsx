import React from "react";
import AddClientModal from "./components/AddClientModal";
import ClientPageHeader from "./components/ClientPageHeader";
import ClientListPanel from "./components/ClientListPanel";
import ClientDetailPanel from "./components/ClientDetailPanel";
import { CARD_BG, BORDER_COLOR } from "./constants";
import { useClientPageState } from "./hooks/useClientPageState";
import "./index.less";

const ClientPageTailwind: React.FC = () => {
  const {
    clients,
    selectedClientId,
    setSelectedClientId,
    selectedClient,
    confirmLoading,
    metricsLoading,
    openAddModal,
    editingClient,
    deleteLoadingId,
    metrics,
    form,
    handleOpenCreate,
    handleOpenEdit,
    handleDeleteClient,
    handleSaveClient,
    handleCancelModal,
    loadClientMetrics,
  } = useClientPageState();

  return (
    <>
      <div className="task-list-page-shell client-page">
        <ClientPageHeader onAdd={handleOpenCreate} />

        <div
          className="client-page__workspace"
          style={{
            background: CARD_BG,
            border: `1px solid ${BORDER_COLOR}`,
          }}
        >
          <ClientListPanel
            clients={clients}
            selectedClientId={selectedClientId}
            onSelect={setSelectedClientId}
            onEdit={handleOpenEdit}
            onDelete={handleDeleteClient}
            deleteLoadingId={deleteLoadingId}
          />

          <div className="client-page__detail">
            {!selectedClient ? (
              <div className="client-page__empty">
                <div className="client-page__empty-title">选择一个 Client</div>
                <div className="client-page__empty-desc">
                  左侧选择节点后，可查看基础信息、健康状态与核心运行指标。
                </div>
              </div>
            ) : (
              <ClientDetailPanel
                selectedClient={selectedClient}
                selectedClientId={selectedClientId}
                metrics={metrics}
                metricsLoading={metricsLoading}
                onRefresh={loadClientMetrics}
              />
            )}
          </div>
        </div>
      </div>

      <AddClientModal
        open={openAddModal}
        form={form}
        mode={editingClient ? "edit" : "create"}
        initialValues={editingClient}
        confirmLoading={confirmLoading}
        onCancel={handleCancelModal}
        onSubmit={handleSaveClient}
      />
    </>
  );
};

export default ClientPageTailwind;
