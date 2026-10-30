import React from "react";
import "./index.less";

type TaskListPageHeaderProps = {
  /** @deprecated 页面级标题已按 AGENTS.md 移除，保留参数以免调用方大面积改动 */
  icon?: React.ReactNode;
  /** @deprecated 页面级标题已按 AGENTS.md 移除，保留参数以免调用方大面积改动 */
  title?: React.ReactNode;
  actions?: React.ReactNode;
  children?: React.ReactNode;
  className?: string;
};

const TaskListPageHeader: React.FC<TaskListPageHeaderProps> = ({
  actions,
  children,
  className = "",
}) => {
  if (!actions && !children) {
    return null;
  }

  return (
    <section
      className={["task-list-page-header", className]
        .filter(Boolean)
        .join(" ")}
    >
      {actions ? (
        <div className="task-list-page-header__top">
          <div className="task-list-page-header__actions">{actions}</div>
        </div>
      ) : null}

      {children}
    </section>
  );
};

export default TaskListPageHeader;
