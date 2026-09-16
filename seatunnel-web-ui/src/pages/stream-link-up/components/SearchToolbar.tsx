import AdvancedSearchForm from '@/pages/batch-link-up/components/SyncTaskList/components/AdvancedSearchForm';

interface SearchToolbarProps {
  initialValues?: any;
  onSearch: (values: any) => void;
  onReset: () => void;
}

/** 实时任务沿用离线任务的筛选布局，保证四类链路的检索体验一致。 */
const SearchToolbar: React.FC<SearchToolbarProps> = ({
  initialValues,
  onSearch,
  onReset,
}) => (
  <AdvancedSearchForm
    initialValues={initialValues}
    onSearch={onSearch}
    onReset={onReset}
    showTableFilters
  />
);

export default SearchToolbar;
