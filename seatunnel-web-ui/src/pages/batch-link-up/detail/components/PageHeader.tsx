import { ArrowLeftOutlined } from "@ant-design/icons";
import { Button } from "antd";
import "./PageHeader.less";

interface Props {
  onBack: () => void;
  /** @deprecated 页级标题已按 AGENTS.md 移除，保留参数以免调用方大面积改动 */
  title?: string;
  /** @deprecated 详情页统一暗色工具栏 */
  theme?: "light" | "dark";
  className?: string;
}

const PageHeader: React.FC<Props> = ({ onBack, className }) => {
  return (
    <div className={["detail-page-toolbar", className].filter(Boolean).join(" ")}>
      <div className="detail-page-toolbar__inner">
        <Button
          type="default"
          icon={<ArrowLeftOutlined />}
          onClick={onBack}
          className="detail-page-toolbar__back"
        >
          返回上一页
        </Button>
      </div>
    </div>
  );
};

export default PageHeader;
