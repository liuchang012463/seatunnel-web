import { ArrowLeftOutlined, ProductOutlined } from "@ant-design/icons";
import { Button } from "antd";
import "./PageHeader.less";

interface Props {
  onBack: () => void;
  title?: string;
  /** Light pages need a readable back button instead of the dark-canvas styling. */
  theme?: "light" | "dark";
  className?: string;
}

const PageHeader: React.FC<Props> = ({
  onBack,
  title = "批量数据引接 · 基础配置",
  theme = "dark",
  className,
}) => {
  const backClassName = theme === "light"
    ? "!h-11 !rounded-xl !border !border-[#D0D5DD] !bg-white !px-5 !font-medium !text-[#344054] shadow-sm transition-all duration-200 hover:!border-[#84CAFF] hover:!bg-[#F5FAFF] hover:!text-[#175CD3]"
    : "!h-11 !rounded-xl !border !border-[#2187A8] !bg-[rgba(33,135,168,0.16)] !px-5 !font-medium !text-[#D5D5D5] shadow-[0_6px_16px_rgba(0,25,34,0.2)] transition-all duration-200 hover:!border-[#4DD2FF] hover:!bg-[rgba(33,135,168,0.32)] hover:!text-white";

  return (
    <div className={["border-b border-[#F2F4F7] bg-white", className].filter(Boolean).join(" ")}>
      <div className="mx-auto flex max-w-[1540px] items-center justify-between gap-4 px-6 py-4">
        <div className="flex min-w-0 items-center gap-4">
          <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-[#EFF8FF] text-[20px] text-[#1570EF]" aria-hidden="true">
            <ProductOutlined />
          </div>

          <div className="min-w-0">
            <h1 className="m-0 text-[22px] font-semibold leading-8 text-[#101828]">{title}</h1>
          </div>
        </div>

        <Button
          type="text"
          icon={<ArrowLeftOutlined />}
          onClick={onBack}
          className={theme === "light"
            ? `${backClassName} page-header__back--light`
            : backClassName}
        >
          返回上一页
        </Button>
      </div>
    </div>
  );
};

export default PageHeader;
