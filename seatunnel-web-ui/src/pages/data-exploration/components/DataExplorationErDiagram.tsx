import {
  EyeInvisibleOutlined,
  EyeOutlined,
  LeftOutlined,
  RightOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { Input } from 'antd';
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
}) => {
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [diagram, setDiagram] = useState<ErDiagram>();
  const [selectedId, setSelectedId] = useState<string>();
  const [searchKeyword, setSearchKeyword] = useState('');
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [hiddenDescriptionIds, setHiddenDescriptionIds] = useState<Set<string>>(new Set());
  // Both side drawers start collapsed so the ER canvas gets the full width.
  const [sidebarCollapsed, setSidebarCollapsed] = useState(true);
  const [detailCollapsed, setDetailCollapsed] = useState(true);
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
    setSearchKeyword('');
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
        setSelectedId(nextDiagram.nodes[0]?.id);
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

  const visibleTables = useMemo(() => {
    const keyword = searchKeyword.trim().toLowerCase();
    if (!keyword) return diagram?.nodes || [];
    return (diagram?.nodes || []).filter((table) => (
      tableTitle(table).toLowerCase().includes(keyword)
      || table.name.toLowerCase().includes(keyword)
      || table.fullyQualifiedName.toLowerCase().includes(keyword)
    ));
  }, [diagram, searchKeyword]);

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

  const selectTableItem = (table: DataExplorationErNode) => {
    setSelectedId(table.id);
    const node = nodes.find((candidate) => candidate.id === table.id);
    if (node) {
      flowInstanceRef.current?.setCenter(
        node.position.x + TABLE_NODE_WIDTH / 2,
        node.position.y + erNodeHeight(table, expandedIds.has(table.id)) / 2,
        { duration: 700 },
      );
    }
  };

  return (
    <main
      className={`er-dialog-content${sidebarCollapsed ? ' is-sidebar-collapsed' : ''}${detailCollapsed ? ' is-detail-collapsed' : ''}`}
    >
        <aside className="sidebar">
          <div className="table-list">
            <div className="side-head">
              <strong>数据表</strong>
              <div className="side-head__right">
                <span>{diagram?.nodes.length || 0}</span>
                <button
                  type="button"
                  className="panel-toggle"
                  title="收起数据表栏"
                  onClick={() => setSidebarCollapsed(true)}
                >
                  <LeftOutlined />
                </button>
              </div>
            </div>
            <Input
              allowClear
              prefix={<SearchOutlined />}
              className="search"
              placeholder="搜索数据表"
              value={searchKeyword}
              onChange={(event) => setSearchKeyword(event.target.value)}
            />
            <div className="table-items">
              {visibleTables.map((table) => (
                <button
                  type="button"
                  key={table.id}
                  className={`table-item${table.id === selectedId ? ' active' : ''}`}
                  onClick={() => selectTableItem(table)}
                >
                  <span aria-hidden="true">▦</span>
                  <div>
                    <b>{tableTitle(table)}</b>
                    <small title={table.fullyQualifiedName}>
                      {table.fullyQualifiedName} · {table.columns.length} 字段
                    </small>
                  </div>
                </button>
              ))}
              {!visibleTables.length && !loading && (
                <div className="empty-hint">暂无匹配的数据表</div>
              )}
            </div>
          </div>
        </aside>

        <div className="flow-wrapper">
          {sidebarCollapsed && (
            <button
              type="button"
              className="edge-toggle edge-toggle--left"
              title="展开数据表栏"
              onClick={() => setSidebarCollapsed(false)}
            >
              <RightOutlined />
            </button>
          )}
          {detailCollapsed && (
            <button
              type="button"
              className="edge-toggle edge-toggle--right"
              title="展开字段栏"
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
                onNodeClick={(_, node) => setSelectedId(node.id)}
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
                  title="收起字段栏"
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
              <div className="tabs"><b>字段</b></div>
              <div className="field-list">
                {selectedTable.columns.map((column) => {
                  const key = columnKey(column);
                  return (
                    <div key={column.id || column.name}>
                      <i className={constraintClass(key || '')}>{key || '·'}</i>
                      <span title={column.name}>{column.name}</span>
                      <small title={column.dataType}>{column.dataType || '-'}</small>
                    </div>
                  );
                })}
              </div>
              <section className="relationship">
                <strong>关联关系</strong>
                <p>{selectedRelationCount ? `该表参与 ${selectedRelationCount} 个关联关系` : '暂无关联关系'}</p>
              </section>
            </>
          ) : (
            <>
              <div className="detail-empty-actions">
                <button
                  type="button"
                  className="panel-toggle"
                  title="收起字段栏"
                  onClick={() => setDetailCollapsed(true)}
                >
                  <RightOutlined />
                </button>
              </div>
              <div className="detail-empty">请选择一张数据表</div>
            </>
          )}
          {/* PK/FK legend pinned to the bottom of the field panel, outside
              the scrolling field list. */}
          <div className="legend">
            <p><i className="pk">PK</i> 主键</p>
            <p><i className="fk">FK</i> 外键</p>
          </div>
        </aside>
    </main>
  );
};

export default DataExplorationErDiagram;
