const fs = require('fs');
const p = 'src/pages/data-source/components/AddOrEditDataSourceModal.tsx';
let text = fs.readFileSync(p, 'utf8');

const old = `    open: ({
      operateType: nextOperateType,
      currentRecord: nextRecord,
      onSuccess,
      dbType,
      hideBack,
      defaultUsage: requestedUsage,
      usageOnly: requestedUsageOnly,
    }: DataSourceModalOpenPayload) => {
      /**
       * 每次打开前，先清理上一次弹窗状态。
       * 这里非常重要，避免 MySQL / PostgreSQL 动态表单互相污染。
       */
      resetModalState();

      setOpen(true);
      setOperateType(nextOperateType);
      setCurrentRecord(nextRecord);
      setDefaultUsage(requestedUsage);
      setUsageOnly(Boolean(requestedUsageOnly));
      successCallbackRef.current = onSuccess;

      /**
       * 编辑模式：保持原来的逻辑。
       * 编辑时直接进入表单页，不显示“上一步”。
       */
      if (nextOperateType === ('EDIT' as DataSourceOperateType) && nextRecord) {
        setSelectedDbType(nextRecord.dbType || '');
        setShowFormStep(true);
        setHideBackButton(true);
        initializeEditForm(nextRecord);
        return;
      }`;

const neu = `    open: ({
      operateType: nextOperateType,
      currentRecord: nextRecord,
      onSuccess,
      dbType,
      hideBack,
      defaultUsage: requestedUsage,
      usageOnly: requestedUsageOnly,
      hideSinkUsage: requestedHideSinkUsage,
    }: DataSourceModalOpenPayload) => {
      /**
       * 每次打开前，先清理上一次弹窗状态。
       * 这里非常重要，避免 MySQL / PostgreSQL 动态表单互相污染。
       */
      resetModalState();

      setOpen(true);
      setOperateType(nextOperateType);
      setCurrentRecord(nextRecord);
      setDefaultUsage(requestedHideSinkUsage ? 'SOURCE' : requestedUsage);
      setUsageOnly(Boolean(requestedUsageOnly));
      setHideSinkUsage(Boolean(requestedHideSinkUsage));
      successCallbackRef.current = onSuccess;

      /**
       * 编辑模式：保持原来的逻辑。
       * 编辑时直接进入表单页，不显示“上一步”。
       */
      if (nextOperateType === ('EDIT' as DataSourceOperateType) && nextRecord) {
        setSelectedDbType(nextRecord.dbType || '');
        setShowFormStep(true);
        setHideBackButton(true);
        initializeEditForm(nextRecord, Boolean(requestedHideSinkUsage));
        return;
      }`;

if (!text.includes(old)) {
  console.error('block missing');
  process.exit(1);
}
text = text.replace(old, neu);

const old2 = `          defaultUsage={defaultUsage}
          usageOnly={usageOnly}
        />`;
const neu2 = `          defaultUsage={defaultUsage}
          usageOnly={usageOnly}
          hideSinkUsage={hideSinkUsage}
        />`;
if (!text.includes(old2)) {
  console.error('props missing');
  process.exit(1);
}
text = text.replace(old2, neu2);
fs.writeFileSync(p, text);
console.log('ok');
