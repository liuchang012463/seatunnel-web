import {
  EyeInvisibleOutlined,
  EyeOutlined,
  LeftOutlined,
  RightOutlined,
} from '@ant-design/icons';
import React, { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import ReactFlow, {
  Background,
  BackgroundVariant,
  Controls,
  Handle,
  MiniMap,
  Position,
  useUpdateNodeInternals,
  type Edge,
  type Node,
  type NodeProps,
  type ReactFlowInstance,
} from 'reactflow';
import 'reactflow/dist/style.css';
import { fetchDataExplorationErDiagram } from '@/pages/data-source/service';
import type {
  DataExplorationErColumn,
  DataExplorationErDiagram as ErDiagram,
  DataExplorationErNode,
} from '@/pages/data-source/types';
import './DataExplorationErDiagram.less';

interface DataExplorationErDiagramProps {
  /** When false the workspace resets and stops rendering its content. */
  active: boolean;
  dataSourceId?: string;
  databaseFqn?: string;
  schemaFqn?: string;
  /** 与左侧主选表入口同步的当前表 */
  selectedTableId?: string;
  onSelectTable?: (tableId: string) => void;
  /** 明确进入表详情（列/样本/指标），避免在 ER 侧再铺一遍字段列表 */
  onOpenDetail?: (tableId: string) => void;
}

interface ErNodeData {
  table: DataExplorationErNode;
  selected: boolean;
  expanded: boolean;
  showDescriptions: boolean;
  onToggle: () => void;
  onToggleDescriptions: () => void;
}

const TABLE_NODE_WIDTH = 300;
const TABLE_COLUMN_COUNT = 3;
const TABLE_COLUMN_GAP = 120;
const TABLE_ROW_GAP = 70;
const TABLE_MARGIN = 30;

function tableTitle(table: DataExplorationErNode) {
  return table.name || table.displayName || table.fullyQualifiedName;
}

function shortText(value: string | undefined, max = 12) {
  const text = value || '';
  return text.length > max ? `${text.slice(0, max)}...` : text;
}

function constraintClass(value: string) {
  if (value === 'PK') return 'pk';
  if (value === 'FK') return 'fk';
  return '';
}

function columnKey(column: DataExplorationErColumn) {
  const constraints = (column.constraints || []).map((constraint) => String(constraint).toUpperCase());
  if (constraints.includes('PRIMARY_KEY')) return 'PK';
  if (constraints.includes('FOREIGN_KEY')) return 'FK';
  return undefined;
}

function erNodeHeight(table: DataExplorationErNode, expanded: boolean) {
  const visibleCount = expanded ? table.columns.length : Math.min(table.columns.length, 5);
  return 38 + visibleCount * 30 + (table.columns.length > 5 ? 30 : 0);
}

const ErTableNode: React.FC<NodeProps<ErNodeData>> = ({ id, data }) => {
  const updateNodeInternals = useUpdateNodeInternals();
  const columns = data.table.columns || [];
  const visibleColumns = data.expanded ? columns : columns.slice(0, 5);

  useLayoutEffect(() => {
    updateNodeInternals(id);
  }, [data.expanded, data.showDescriptions, id, updateNodeInternals]);

  return (
    <article className={`er-table${data.selected ? ' selected' : ''}`}>
      <header>
        <span aria-hidden="true">▦</span>
        <strong title={tableTitle(data.table)}>{tableTitle(data.table)}</strong>
        <small title={data.table.fullyQualifiedName}>{data.table.fullyQualifiedName}</small>
        <button
          type="button"
          className="comment-toggle"
          title={data.showDescriptions ? '隐藏字段注释' : '显示字段注释'}
          onClick={(event) => {
            event.stopPropagation();
            data.onToggleDescriptions();
          }}
        >
          {data.showDescriptions ? <EyeOutlined /> : <EyeInvisibleOutlined />}
        </button>
      </header>
      <div className="fields">
        {visibleColumns.map((column) => {
          const key = columnKey(column);
          return (
            <div key={column.id || column.name} className="field">
              <i className={constraintClass(key || '')}>{key || ''}</i>
              <b className="field-name" title={column.name}>{shortText(column.name)}</b>
              {data.showDescriptions && (
                <span className="field-comment" title={column.description}>{shortText(column.description)}</span>
              )}
              <small title={column.dataType}>{column.dataType || '-'}</small>
            </div>
          );
        })}
      </div>
      {columns.length > 5 && (
        <button
          type="button"
          className="more-fields"
          onClick={(event) => {
            event.stopPropagation();
            data.onToggle();
          }}
        >
          {data.expanded ? '收起字段' : `显示更多（${columns.length - 5}）`}
        </button>
      )}
      <Handle type="source" position={Position.Right} className="table-handle" />
      <Handle type="target" position={Position.Left} className="table-handle" />
    </article>
  );
};

const nodeTypes = { table: ErTableNode };

const DataExplorationErDiagram: React.FC<DataExplorationErDiagramProps> = ({
  active,
  dataSourceId,
  databaseFqn,
  schemaFqn,
  selectedTableId,
  onSelectTable,
  onOpenDetail,
}) => {
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [diagram, setDiagram] = useState<ErDiagram>();
  const [selectedId, setSelectedId] = useState<string>();
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [hiddenDescriptionIds, setHiddenDescriptionIds] = useState<Set<string>>(new Set());
  // 右侧仅保留「选中摘要 + 打开详情」；默认展开便于发现下钻入口
  const [detailCollapsed, setDetailCollapsed] = useState(false);
  const flowInstanceRef = useRef<ReactFlowInstance | null>(null);

  useEffect(() => {
    if (!active || !dataSourceId || !databaseFqn) {
      setLoading(false);
      setLoadError('');
      setDiagram(undefined);
      setSelectedId(undefined);
      return undefined;
    }

    let disposed = false;
    setLoading(true);
    setLoadError('');
    setDiagram(undefined);
    setSelectedId(undefined);
    setExpandedIds(new Set());
    setHiddenDescriptionIds(new Set());

    fetchDataExplorationErDiagram(dataSourceId, databaseFqn, schemaFqn)
      .then((response) => {
        if (disposed) return;
        if (response.code !== 0) {
          setLoadError(response.message || '加载 ER 图失败');
          return;
        }
        const nextDiagram = response.data || {
          databaseFqn,
          schemaFullyQualifiedName: schemaFqn,
          nodes: [],
          edges: [],
        };
        setDiagram(nextDiagram);
        setSelectedId(selectedTableId || nextDiagram.nodes[0]?.id);
      })
      .catch((error: any) => {
        if (disposed) return;
        setLoadError(
          error?.response?.data?.message
            || error?.response?.data?.error
            || error?.response?.data?.detail
            || error?.message
            || '加载 ER 图失败',
        );
      })
      .finally(() => {
        if (!disposed) setLoading(false);
      });

    return () => {
      disposed = true;
    };
  }, [active, dataSourceId, databaseFqn, schemaFqn]);

  // 跟随左侧主选表，避免 ER 与目录两套选中态
  useEffect(() => {
    if (!selectedTableId || !diagram?.nodes.some((table) => table.id === selectedTableId)) {
      return;
    }
    setSelectedId(selectedTableId);
  }, [diagram, selectedTableId]);

  const selectedTable = diagram?.nodes.find((table) => table.id === selectedId);
  const selectedRelationCount = (diagram?.edges || []).filter((edge) => (
    edge.source.nodeId === selectedId || edge.target.nodeId === selectedId
  )).length;

  const nodes = useMemo<Node<ErNodeData>[]>(() => {
    if (!diagram) return [];

    const rowHeights: number[] = [];
    diagram.nodes.forEach((table, index) => {
      const row = Math.floor(index / TABLE_COLUMN_COUNT);
      rowHeights[row] = Math.max(
        rowHeights[row] || 0,
        erNodeHeight(table, expandedIds.has(table.id)),
      );
    });

    const rowTops = rowHeights.reduce<number[]>((tops, height, index) => {
      tops[index] = index === 0
        ? TABLE_MARGIN
        : tops[index - 1] + rowHeights[index - 1] + TABLE_ROW_GAP;
      return tops;
    }, []);

    return diagram.nodes.map((table, index) => {
      const column = index % TABLE_COLUMN_COUNT;
      const row = Math.floor(index / TABLE_COLUMN_COUNT);
      return {
        id: table.id,
        type: 'table',
        position: {
          x: TABLE_MARGIN + column * (TABLE_NODE_WIDTH + TABLE_COLUMN_GAP),
          y: rowTops[row] || TABLE_MARGIN,
        },
        data: {
          table,
          selected: table.id === selectedId,
          expanded: expandedIds.has(table.id),
          showDescriptions: !hiddenDescriptionIds.has(table.id),
          onToggle: () => setExpandedIds((current) => {
            const next = new Set(current);
            if (next.has(table.id)) next.delete(table.id);
            else next.add(table.id);
            return next;
          }),
          onToggleDescriptions: () => setHiddenDescriptionIds((current) => {
            const next = new Set(current);
            if (next.has(table.id)) next.delete(table.id);
            else next.add(table.id);
            return next;
          }),
        },
      };
    });
  }, [diagram, expandedIds, hiddenDescriptionIds, selectedId]);

  const edges = useMemo<Edge[]>(() => (
    (diagram?.edges || []).map((edge) => {
      const highlighted = edge.source.nodeId === selectedId || edge.target.nodeId === selectedId;
      return {
        id: edge.id,
        source: edge.source.nodeId,
        target: edge.target.nodeId,
        type: 'smoothstep',
        style: {
          stroke: '#4f8cff',
          strokeWidth: highlighted ? 2 : 1.5,
          opacity: highlighted ? 0.95 : 0.62,
        },
      };
    })
  ), [diagram, selectedId]);

  const focusTableOnCanvas = (table: DataExplorationErNode) => {
    const node = nodes.find((candidate) => candidate.id === table.id);
    if (node) {
      flowInstanceRef.current?.setCenter(
        node.position.x + TABLE_NODE_WIDTH / 2,
        node.position.y + erNodeHeight(table, expandedIds.has(table.id)) / 2,
        { duration: 700 },
      );
    }
  };

  const selectTableOnCanvas = (tableId: string) => {
    setSelectedId(tableId);
    onSelectTable?.(tableId);
    const table = diagram?.nodes.find((item) => item.id === tableId);
    if (table) focusTableOnCanvas(table);
  };

  return (
    <main
      className={`er-dialog-content is-sidebar-collapsed${detailCollapsed ? ' is-detail-collapsed' : ''}`}
    >
        <div className="flow-wrapper">
          <div className="er-mode-banner">
            <strong>关系总览</strong>
            <p>查看表关系与主键/外键；在图中点选表后点右侧「打开表详情」，或直接从左侧「选择表」进入详情</p>
          </div>
          {detailCollapsed && (
            <button
              type="button"
              className="edge-toggle edge-toggle--right"
              title="展开选中表摘要"
              onClick={() => setDetailCollapsed(false)}
            >
              <LeftOutlined />
            </button>
          )}
          {loading && <div className="flow-mask">正在加载 ER 图...</div>}
          {!loading && loadError && <div className="flow-mask error">{loadError}</div>}
          {!loading && !loadError && !diagram?.nodes.length && (
            <div className="flow-mask">当前 Schema 暂无数据表</div>
          )}
          {diagram?.nodes.length ? (
            <div className="flow-canvas">
              <ReactFlow
                nodes={nodes}
                edges={edges}
                nodeTypes={nodeTypes}
                minZoom={0.4}
                maxZoom={2}
                defaultViewport={{ x: 40, y: 45, zoom: 0.8 }}
                fitView
                onInit={(instance) => {
                  flowInstanceRef.current = instance;
                }}
                onNodeClick={(_, node) => selectTableOnCanvas(node.id)}
                onNodeDoubleClick={(_, node) => onOpenDetail?.(node.id)}
                proOptions={{ hideAttribution: true }}
              >
                <Background variant={BackgroundVariant.Dots} gap={18} size={1} color="#2a3a52" />
                <Controls showInteractive={false} />
                <MiniMap pannable zoomable nodeColor={() => '#4f8cff'} />
              </ReactFlow>
            </div>
          ) : null}
        </div>

        <aside className="detail-panel">
          {selectedTable ? (
            <>
              <div className="detail-head">
                <button
                  type="button"
                  className="panel-toggle"
                  title="收起摘要栏"
                  onClick={() => setDetailCollapsed(true)}
                >
                  <RightOutlined />
                </button>
                <span aria-hidden="true">▦</span>
                <div>
                  <strong title={tableTitle(selectedTable)}>{tableTitle(selectedTable)}</strong>
                  <small title={selectedTable.fullyQualifiedName}>{selectedTable.fullyQualifiedName}</small>
                </div>
              </div>
              <div className="er-detail-summary">
                <p><b>{selectedTable.columns.length}</b> 个字段（图上仅作关系概览）</p>
                <p>
                  {selectedRelationCount
                    ? `参与 ${selectedRelationCount} 个关联关系`
                    : '暂无关联关系'}
                </p>
                <p className="er-detail-hint">完整列定义、样本与探查指标请打开表详情，避免与总览重复罗列。</p>
                <button
                  type="button"
                  className="er-open-detail"
                  onClick={() => onOpenDetail?.(selectedTable.id)}
                >
                  打开表详情
                </button>
                <small className="er-detail-shortcut">也可双击画布上的表节点</small>
              </div>
            </>
          ) : (
            <>
              <div className="detail-empty-actions">
                <button
                  type="button"
                  className="panel-toggle"
                  title="收起摘要栏"
                  onClick={() => setDetailCollapsed(true)}
                >
                  <RightOutlined />
                </button>
              </div>
              <div className="detail-empty">在图中点选一张表，或从左侧主列表进入详情</div>
            </>
          )}
          <div className="legend">
            <p><i className="pk">PK</i> 主键</p>
            <p><i className="fk">FK</i> 外键</p>
          </div>
        </aside>
    </main>
  );
};

export default DataExplorationErDiagram;
