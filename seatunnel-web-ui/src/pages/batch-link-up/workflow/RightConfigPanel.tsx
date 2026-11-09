import { Settings2 } from "lucide-react";
import { useState, type ReactNode } from "react";
import { getTabDefinitions } from "./configDefinition";
import styles from "./index.less";
import type { TabKey } from "./types";

interface RightConfigPanelProps {
  activeTab?: TabKey | null;
  onTabChange?: (tab: TabKey | null) => void;
  allowedTabs?: TabKey[];
  contentDisabled?: boolean;
  tabNotices?: Partial<Record<TabKey, ReactNode>>;
  params?: any;
  basicConfig?: any;
  setBasicConfig?: React.Dispatch<React.SetStateAction<any>>;
  scheduleConfig?: any;
  setScheduleConfig?: React.Dispatch<React.SetStateAction<any>>;
  envConfig?: any;
  setEnvConfig?: React.Dispatch<React.SetStateAction<any>>;
}

export default function RightConfigPanel({
  activeTab = null,
  onTabChange,
  allowedTabs,
  contentDisabled = false,
  tabNotices,
  params,
  basicConfig,
  setBasicConfig,
  scheduleConfig,
  setScheduleConfig,
  envConfig,
  setEnvConfig,
}: RightConfigPanelProps) {
  const [hoveredTab, setHoveredTab] = useState<string | null>(null);

  const tabDefinitions = getTabDefinitions(
    params,
    basicConfig,
    setBasicConfig,
    scheduleConfig,
    setScheduleConfig,
    envConfig,
    setEnvConfig
  );

  const visibleTabDefinitions = allowedTabs
    ? tabDefinitions.filter((item: any) => allowedTabs.includes(item.key))
    : tabDefinitions;

  const currentContent = activeTab
    ? visibleTabDefinitions.find((item: any) => item.key === activeTab)?.content
    : null;
  const activeNotice = activeTab ? tabNotices?.[activeTab] : null;

  const handleTabClick = (tab: TabKey) => {
    if (activeTab === tab) {
      onTabChange?.(null);
      return;
    }

    onTabChange?.(tab);
  };

  const opened = !!activeTab;

  return (
    <div
      className={`workflow-config-side-panel ${styles.sidePanel} ${
        opened ? styles.sidePanelOpened : styles.sidePanelCollapsed
      }`}
    >
      <div className={styles.sidePanelContentWrap}>
        {opened && (
          <div className={styles.sidePanelMain}>
            <div className={styles.sidePanelHeader}>
              <div
                className={styles.sidePanelIconTitle}
                style={{ fontWeight: 600, fontSize: 15 }}
              >
                配置面板
              </div>
            </div>

            <div className={styles.sidePanelBody}>
              {activeNotice}
              {contentDisabled ? (
                <fieldset disabled style={{ border: 0, margin: 0, minWidth: 0, padding: 0 }}>
                  {currentContent}
                </fieldset>
              ) : currentContent}
            </div>
          </div>
        )}

        <div className={styles.sidePanelTabs}>
          <div className={styles.sidePanelTabsTop}>
            <div className={styles.sidePanelSettingIcon}>
              <Settings2 size={16} strokeWidth={1.9} />
            </div>
          </div>

          <div className={styles.sidePanelTabList}>
            {visibleTabDefinitions.map((item: any) => {
              const active = activeTab === item.key;
              const hovered = hoveredTab === item.key;

              return (
                <div
                  key={item.key}
                  onClick={() => handleTabClick(item.key)}
                  onMouseEnter={() => setHoveredTab(item.key)}
                  onMouseLeave={() => setHoveredTab(null)}
                  className={`${styles.sidePanelTab} ${
                    active ? styles.sidePanelTabActive : ""
                  } ${hovered ? styles.sidePanelTabHovered : ""}`}
                >
                  {item.label}
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}
