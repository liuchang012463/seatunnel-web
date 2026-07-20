export interface ParamRow {
    key: string;
    paramName: string;
    paramValue: string;
}

export interface BasicConfig {
    jobName: string;
    jobDesc: string;
    clientId: string;
    mode: string;
    sourceType: string;
    targetType: string;
    sourceDataSourceId: string | number;
    targetDataSourceId: string | number;
}

export type LinkPriority = "HIGH" | "MEDIUM" | "LOW";

export interface EnvConfig {
  jobMode: "BATCH" | "STREAMING";
  parallelism: number;
  checkpointInterval: number;
  /** 每线程最大读取字节/秒；空=不限速 */
  readLimitBytesPerSecond?: number | null;
  /** 每线程最大读取行/秒；空=不限速 */
  readLimitRowsPerSecond?: number | null;
  /** 仅存储，不参与调度/执行 */
  priority?: LinkPriority;
}

export const defaultEnvConfig: EnvConfig = {
  jobMode: "STREAMING",
  parallelism: 1,
  checkpointInterval: 30000,
  readLimitBytesPerSecond: null,
  readLimitRowsPerSecond: null,
  priority: "MEDIUM",
};

export interface ScheduleConfig {
    // 调度参数
    paramsList: Array<{
        key: string;
        value: string;
        description?: string;
    }>;

    // 调度策略
    instanceGenerateMode: "nextDay" | "immediately";
    scheduleRunType: "normal" | "pause" | "empty";
    timeoutMode: "system" | "custom";
    timeoutValue?: number;
    timeoutUnit?: "minute" | "hour" | "day";
    rerunPolicy: "success_or_fail" | "fail_only" | "disabled";
    autoRetry: boolean;
    retryTimes?: number;
    retryInterval?: number;

    // 调度时间
    scheduleType: "minute" | "hour" | "day" | "week";
    hourMode?: "range" | "appoint";
    hourlyRangeValue?: {
        startTime: string;
        intervalHour: number;
        endTime: string;
    };
    hourlyAppointValue?: {
        hours: number[];
        minute: string;
    };
    minuteValue?: {
        intervalMinute: number;
    };
    dailyValue?: {
        time: string;
    };
    weeklyValue?: {
        weekdays: string[];
        time: string;
    };

    effectType: "forever" | "assign";
    effectStartTime?: string;
    effectEndTime?: string;
    cronExpression?: string;
}
