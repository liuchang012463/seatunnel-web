/** Native maxLength and the Ant Design counter both measure UTF-16 code units. */
export const textLengthRule = (maxLength: number) => ({
  validator: async (_: unknown, value?: string) => {
    if (value && value.length > maxLength) throw new Error(`最多可填写 ${maxLength} 个字符`);
  },
});
