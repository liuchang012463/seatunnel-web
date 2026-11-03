import { ArrowLeftOutlined } from "@ant-design/icons";
import { Button } from "antd";
import "@/pages/batch-link-up/detail/components/PageHeader.less";

interface Props {
  onBack: () => void;
}

const PageHeader: React.FC<Props> = ({ onBack }) => {
  return (
    <div className="detail-page-toolbar">
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
