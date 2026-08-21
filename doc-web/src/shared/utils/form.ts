// 动态表单引擎工具：空值判定、默认值模型构建、只读展示格式化（design/10 §3.3）
import type { AttributeDef } from '@/modules/config/types';
import { formatDecimal, formatTime } from '@/shared/utils/format';

export function isEmptyValue(value: unknown): boolean {
  if (value === undefined || value === null) return true;
  if (typeof value === 'string') return value.trim() === '';
  if (Array.isArray(value)) return value.length === 0;
  return false;
}

/** 按属性类型转换默认值（default_value 仅新增模式回填，prd/06 §4.5） */
function convertDefault(attr: AttributeDef): unknown {
  const raw = attr.default_value;
  if (raw === undefined || raw === '') return attr.data_type === 'boolean' ? false : undefined;
  switch (attr.data_type) {
    case 'integer':
    case 'decimal': {
      const num = Number(raw);
      return Number.isNaN(num) ? undefined : num;
    }
    case 'boolean':
      return raw === 'true';
    default:
      return raw;
  }
}

/** 新增模式的初始模型：visible 属性 + default_value 回填 */
export function buildDefaultModel(attributes: AttributeDef[]): Record<string, unknown> {
  const model: Record<string, unknown> = {};
  for (const attr of attributes.filter((a) => a.visible)) {
    if (attr.data_type === 'table') {
      model[attr.code] = [];
    } else if (attr.data_type === 'boolean') {
      model[attr.code] = convertDefault(attr) ?? false;
    } else {
      model[attr.code] = convertDefault(attr);
    }
  }
  return model;
}

/** 只读展示文本（详情页/只读控件） */
export function formatDisplayValue(attr: AttributeDef, value: unknown): string {
  if (isEmptyValue(value) && attr.data_type !== 'boolean') return '-';
  switch (attr.data_type) {
    case 'boolean':
      return value ? '是' : '否';
    case 'integer':
      return String(value);
    case 'decimal':
      return formatDecimal(value, attr.precision ?? 2);
    case 'date':
      return typeof value === 'number' ? formatTime(value, 'YYYY-MM-DD') : String(value ?? '-');
    case 'datetime':
      return typeof value === 'number' ? formatTime(value) : String(value ?? '-');
    default:
      return String(value);
  }
}
