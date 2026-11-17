import { Outlet } from '@umijs/max';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';

/** Keep validation, pagination and empty states in the lake flow in Chinese. */
export default function LakeLocale() {
  return <ConfigProvider locale={zhCN}><Outlet /></ConfigProvider>;
}
