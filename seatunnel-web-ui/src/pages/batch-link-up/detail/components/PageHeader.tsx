import { ArrowLeftOutlined, ProductOutlined } from "@ant-design/icons";
import { Button } from "antd";

interface Props {
  onBack: () => void;
}

const PageHeader: React.FC<Props> = ({ onBack }) => {
  return (
    <div className="border-b border-[#F2F4F7] bg-white">
      <div className="mx-auto flex max-w-[1540px] items-center justify-between gap-4 px-6 py-5">
        <div className="flex min-w-0 items-center gap-4">
          <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-[#EFF8FF] text-[20px] text-[#1570EF]">
            <ProductOutlined />
          </div>

          <div className="min-w-0">
            <div className="text-[22px] font-semibold leading-8 text-[#101828]">
              物理路由配置
            </div>
            <div className="mt-1 text-[14px] leading-6 text-[#667085]">
              配置引接链路的物理路由：数据源、目标端与执行客户端等接入路径。
            </div>
          </div>
        </div>

        <Button
          type="text"
          icon={<ArrowLeftOutlined />}
          onClick={onBack}
          className="!h-11 !rounded-xl !border !border-[#2187A8] !bg-[rgba(33,135,168,0.16)] !px-5 !font-medium !text-[#D5D5D5] shadow-[0_6px_16px_rgba(0,25,34,0.2)] transition-all duration-200 hover:!border-[#4DD2FF] hover:!bg-[rgba(33,135,168,0.32)] hover:!text-white"
        >
          返回上一页
        </Button>
      </div>
    </div>
  );
};

export default PageHeader;
