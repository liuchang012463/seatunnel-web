module.exports = {
  content: [
    "./src/**/*.{js,jsx,ts,tsx}",
    "./pages/**/*.{js,jsx,ts,tsx}",
    "./components/**/*.{js,jsx,ts,tsx}",
  ],
  theme: {
    extend: {
      // The design tokens are px-based while the root font-size is 14px, so the default
      // rem-based scale rendered 10.5px/12.25px. Pin the common steps to the token sizes.
      fontSize: {
        xs: ["12px", "18px"],
        sm: ["13px", "20px"],
        base: ["14px", "22px"],
        lg: ["16px", "24px"],
        xl: ["20px", "28px"],
        "2xl": ["24px", "32px"],
      },
    },
  },
};
