import React from "react";
import "./index.less";

type TaskListPageHeaderProps = {
  icon: React.ReactNode;
  title: React.ReactNode;
  subtitle: React.ReactNode;
  actions?: React.ReactNode;
  children?: React.ReactNode;
  className?: string;
};

const TaskListPageHeader: React.FC<TaskListPageHeaderProps> = ({
  icon,
  title,
  subtitle,
  actions,
  children,
  className = "",
}) => {
  return (
    <section
      className={["task-list-page-header", className]
        .filter(Boolean)
        .join(" ")}
    >
      <div className="task-list-page-header__top">
        <div className="task-list-page-header__meta">
          <div className="task-list-page-header__icon">{icon}</div>

          <div className="task-list-page-header__text">
            <h1 className="task-list-page-header__title">{title}</h1>
            <p className="task-list-page-header__subtitle">{subtitle}</p>
          </div>
        </div>

        {actions ? (
          <div className="task-list-page-header__actions">{actions}</div>
        ) : null}
      </div>

      {children}
    </section>
  );
};

export default TaskListPageHeader;
