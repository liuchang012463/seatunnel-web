import CommonClientLinkSection, {
  ConnectivityStatus,
} from "@/pages/common/components/CommonClientLinkSection";
import { generateTaskTargetDataSourceOptions } from "@/pages/batch-link-up/DataSourceSelect";

interface Props {
  activeStep: "base" | "client";
  sourceType: any;
  targetType: any;
  sourceLabel: string;
  targetLabel: string;
  clientId: any;
  setClientId: (ids: string) => void;
  handleSourceChange: (value: string, option: any) => void;
  handleTargetChange: (value: string, option: any) => void;

  sourceDataSourceId?: string;
  targetDataSourceId?: string;
  setSourceDataSourceId: (id?: string) => void;
  setTargetDataSourceId: (id?: string) => void;

  sourceTestStatus: ConnectivityStatus;
  targetTestStatus: ConnectivityStatus;
  setSourceTestStatus: (status: ConnectivityStatus) => void;
  setTargetTestStatus: (status: ConnectivityStatus) => void;

  sectionRef?: React.RefObject<HTMLDivElement>;
}

const RealtimeClientLinkSection: React.FC<Props> = (props) => {
  return (
    <CommonClientLinkSection
      {...props}
      scene="realtime"
      clientLabel="数据引接引擎"
      clientPlaceholder="请选择数据引接引擎节点"
      sourceTitle="实时来源"
      targetTitle="实时去向"
      sourceCreateText="新建实时来源数据源"
      targetCreateText="新建实时去向数据源"
      targetDataSourceTypeOptions={generateTaskTargetDataSourceOptions()}
      getVerifyExtraParams={({ side }) => ({
        linkType: "REALTIME",
        roleScene: side === "source" ? "REALTIME_SOURCE" : "REALTIME_SINK",
      })}
    />
  );
};

export default RealtimeClientLinkSection;