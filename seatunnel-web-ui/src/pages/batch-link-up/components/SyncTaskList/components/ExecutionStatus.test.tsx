import React from "react";
import { render } from "@testing-library/react";

import ExecutionStatus from "./ExecutionStatus";

jest.mock("@umijs/max", () => ({
  useIntl: () => ({
    formatMessage: ({ defaultMessage }: { defaultMessage: string }) => defaultMessage,
  }),
}));

describe("ExecutionStatus metrics availability", () => {
  it("hides counters and rates when nested streaming metrics are unavailable", () => {
    const { container } = render(
      React.createElement(ExecutionStatus, {
        record: {
          jobStatus: "FAILED",
          readBytes: 12_000_000,
          readBps: 9_000_000,
          latestMetrics: { metricsAvailable: false },
        },
      }),
    );

    expect(container.textContent).toContain("R — / W —");
    expect(container.textContent).toContain("—行/秒");
    expect(container.textContent).toContain("大小—");
  });

  it("keeps a reported Engine rate when terminal byte counters are zero", () => {
    const { container } = render(
      React.createElement(ExecutionStatus, {
        record: {
          lastJobStatus: "FAILED",
          lastStartTime: "2026-11-19 10:00:00",
          lastEndTime: "2026-11-19 10:00:02",
          latestMetrics: {
            metricsAvailable: true,
            readBytes: 0,
            writeBytes: 0,
            readQps: 10.9078,
            readBps: 1_234_567,
            writeBps: 987_654,
          },
        },
      }),
    );

    expect(container.textContent).toContain("R 1.3 MB/s / W 1.0 MB/s");
    expect(container.textContent).toContain("QPS11.0行/秒");
  });
});
