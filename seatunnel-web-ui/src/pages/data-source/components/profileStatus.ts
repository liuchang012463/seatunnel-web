import type { StatusChipTone } from '@/components/StatusChip';

export interface ProfileStatusView {
  tone: StatusChipTone;
  text: string;
  detail: string;
}

/** 元数据探查状态 → StatusChip 视图（卡片/列表共用，审计 G6）。 */
export const profileStatusConfig = (profileStatus?: string): ProfileStatusView => {
  if (profileStatus === 'SUCCESS') {
    return { tone: 'success', text: '已探查', detail: '最近一次探查成功' };
  }
  if (profileStatus === 'FAILED') {
    return { tone: 'error', text: '探查异常', detail: '最近一次探查失败，可在探查结果中查看原因' };
  }
  if (profileStatus === 'RUNNING' || profileStatus === 'QUEUED') {
    return { tone: 'processing', text: '探查中', detail: '元数据探查任务执行中' };
  }
  return { tone: 'neutral', text: '未探查', detail: '尚未发起元数据探查' };
};
