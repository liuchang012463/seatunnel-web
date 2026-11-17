import type { LakeReadOnlyQueryResult } from '@/services/lake';
import { defaultQueryColumns, labelQueryResult } from './presentation';

test('默认预览排除不可查询字段，并限制宽表的初始字段数量', () => {
  const columns = [
    { name: 'secret', selectable: false },
    ...Array.from({ length: 25 }, (_, index) => ({
      name: 'field_' + index,
      selectable: true,
    })),
  ];
  expect(defaultQueryColumns(columns)).toEqual(Array.from({ length: 20 }, (_, index) => 'field_' + index));
  expect(defaultQueryColumns([{ name: 'unsupported', selectable: false }])).toEqual([]);
});

const result: LakeReadOnlyQueryResult = {
  columns: ['c0'],
  rows: [{ c0: 7 }],
  rowCount: 1,
  byteCount: 1,
  truncated: false,
  elapsedMillis: 1,
  explain: false,
};

test('预览显示原字段名，同时保留后端别名和行数据', () => {
  const display = labelQueryResult(result, ['event_id']);
  expect(display.columnTitles).toEqual({ c0: 'event_id' });
  expect(display.rows).toBe(result.rows);
  expect(display.columns).toEqual(['c0']);
});

test('两侧同名字段可区分，执行计划保留真实返回列名', () => {
  expect(labelQueryResult(result, ['id'], ['id']).columnTitles).toEqual({
    left_c0: '左表 · id',
    right_c0: '右表 · id',
  });
  expect(labelQueryResult({ ...result, explain: true }, ['id']).columnTitles).toEqual({});
});
