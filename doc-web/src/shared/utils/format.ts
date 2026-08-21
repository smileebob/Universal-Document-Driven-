// 日期与数值格式化（dayjs；接口时间戳为毫秒 Long，prd/11 §一）
import dayjs from 'dayjs';

export function formatTime(ts?: number | null, pattern = 'YYYY-MM-DD HH:mm:ss'): string {
  if (!ts) return '-';
  return dayjs(ts).format(pattern);
}

export function formatDate(ts?: number | null): string {
  return formatTime(ts, 'YYYY-MM-DD');
}

/** decimal 按精度格式化（列表/详情右对齐展示，design/10 §3.2） */
export function formatDecimal(value: unknown, precision = 2): string {
  if (value === null || value === undefined || value === '') return '-';
  const num = Number(value);
  if (Number.isNaN(num)) return String(value);
  return num.toFixed(precision);
}
