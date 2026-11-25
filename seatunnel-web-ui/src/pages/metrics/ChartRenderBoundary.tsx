import { Alert, Button } from 'antd';
import { Component, type ReactNode } from 'react';

class ChartRenderBoundary extends Component<{ title: string; children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error: Error) {
    console.error('[任务概览] 图表渲染失败', this.props.title, error);
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <Alert
        type="error"
        showIcon
        message="图表显示失败"
        description="请重试显示此图表。其他图表不受影响。"
        action={
          <Button
            size="small"
            aria-label={`重试显示${this.props.title}`}
            onClick={() => this.setState({ failed: false })}
          >
            重试
          </Button>
        }
      />
    );
  }
}

export default ChartRenderBoundary;
