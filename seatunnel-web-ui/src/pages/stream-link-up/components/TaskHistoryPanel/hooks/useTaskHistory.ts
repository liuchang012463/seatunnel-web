import { streamingSeatunnelJobInstanceApi } from "@/pages/batch-link-up/api";
import { HistoryItem } from "@/pages/batch-link-up/type";
import { normalizeJobStatus } from "@/pages/common/utils/jobStatus";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import {
  getTaskHistoryTimeParams,
  type CustomHistoryTimeRange,
  type TimeRangeType,
} from "@/pages/common/utils/taskHistoryTime";

interface UseTaskHistoryParams {
  selectedItem: any;
  statusFilter: string;
}

export const useTaskHistory = ({
  selectedItem,
  statusFilter,
}: UseTaskHistoryParams) => {
  const [historyItems, setHistoryItems] = useState<HistoryItem[]>([]);
  const [loading, setLoading] = useState(false);

  const [keyword, setKeyword] = useState("");
  const [debouncedKeyword, setDebouncedKeyword] = useState("");
  const [timeRangeType, setTimeRangeType] =
    useState<TimeRangeType>("最近一天");

  const [customTimeRange, setCustomTimeRange] = useState<CustomHistoryTimeRange>(null);

  // 快速切换筛选/任务时，慢响应不能覆盖新请求的结果：只有最新请求可以写状态。
  const requestSeqRef = useRef(0);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setDebouncedKeyword(keyword.trim());
    }, 400);

    return () => window.clearTimeout(timer);
  }, [keyword]);

  const fetchHistory = useCallback(async () => {
    if (!selectedItem?.id) {
      requestSeqRef.current += 1;
      setHistoryItems([]);
      return;
    }

    const { queryStartTime, queryEndTime } = getTaskHistoryTimeParams(timeRangeType, customTimeRange);
    const requestSeq = ++requestSeqRef.current;

    setLoading(true);

    try {
      const data = await streamingSeatunnelJobInstanceApi.page({
        pageNum: 1,
        pageSize: 20,
        jobDefinitionId: selectedItem.id,
        keyword: debouncedKeyword || undefined,
        jobStatus:
          statusFilter && statusFilter !== "all" ? statusFilter : undefined,
        queryStartTime,
        queryEndTime,
      });

      if (requestSeq !== requestSeqRef.current) {
        return;
      }
      if (data?.code === 0) {
        setHistoryItems(data?.data?.bizData || []);
      } else {
        setHistoryItems([]);
        console.warn("运行历史查询失败", { jobDefinitionId: selectedItem.id, code: data?.code });
      }
    } catch {
      if (requestSeq === requestSeqRef.current) {
        setHistoryItems([]);
        console.warn("运行历史请求异常", { jobDefinitionId: selectedItem.id });
      }
    } finally {
      if (requestSeq === requestSeqRef.current) {
        setLoading(false);
      }
    }
  }, [
    selectedItem?.id,
    debouncedKeyword,
    statusFilter,
    timeRangeType,
    customTimeRange,
  ]);

  useEffect(() => {
    fetchHistory();
  }, [fetchHistory]);

  const statusCountMap = useMemo(() => {
    return historyItems.reduce(
      (acc, item) => {
        // Count by the same category the status filter uses; raw CANCELED/SAVEPOINT_DONE etc.
        // must not produce counts that disagree with the filtered list.
        const category = normalizeJobStatus(item.jobStatus);
        if (category === "FINISHED") {
          acc.FINISHED += 1;
        } else if (category === "FAILED") {
          acc.FAILED += 1;
        } else if (category === "RUNNING") {
          acc.RUNNING += 1;
        } else {
          acc.PENDING += 1;
        }
        acc.all += 1;
        return acc;
      },
      {
        all: 0,
        FINISHED: 0,
        FAILED: 0,
        RUNNING: 0,
        PENDING: 0,
      } as Record<string, number>
    );
  }, [historyItems]);

  return {
    historyItems,
    loading,

    keyword,
    setKeyword,

    timeRangeType,
    setTimeRangeType,

    customTimeRange,
    setCustomTimeRange,

    statusCountMap,
    fetchHistory,
  };
};