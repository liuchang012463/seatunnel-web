import { ChevronLeft, ChevronRight } from "lucide-react";
import React, { useCallback, useEffect, useRef, useState } from "react";

export interface FilterOption<T extends string = string> {
  label: string;
  value: T;
}

interface ScrollableFilterProps<T extends string = string> {
  value: T;
  options: FilterOption<T>[];
  onChange: (value: T) => void;
  className?: string;
}

const ScrollableFilter = <T extends string>({
  value,
  options,
  onChange,
  className = "",
}: ScrollableFilterProps<T>) => {
  const scrollRef = useRef<HTMLDivElement>(null);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(false);

  const updateScrollState = useCallback(() => {
    const element = scrollRef.current;

    if (!element) {
      return;
    }

    const maxScrollLeft = element.scrollWidth - element.clientWidth;

    setCanScrollLeft(element.scrollLeft > 4);
    setCanScrollRight(element.scrollLeft < maxScrollLeft - 4);
  }, []);

  useEffect(() => {
    const element = scrollRef.current;

    if (!element) {
      return;
    }

    updateScrollState();

    element.addEventListener("scroll", updateScrollState, { passive: true });

    const observer = new ResizeObserver(updateScrollState);
    observer.observe(element);

    return () => {
      element.removeEventListener("scroll", updateScrollState);
      observer.disconnect();
    };
  }, [options, updateScrollState]);

  const handleScroll = (direction: "left" | "right") => {
    scrollRef.current?.scrollBy({
      left: direction === "left" ? -260 : 260,
      behavior: "smooth",
    });
  };

  return (
    <div className={`alarm-page__filter group relative min-w-0 ${className}`}>
      <div
        className={[
          "alarm-page__filter-fade alarm-page__filter-fade--left",
          canScrollLeft ? "opacity-100" : "opacity-0",
        ].join(" ")}
      >
        <button
          type="button"
          aria-label="向左查看更多筛选项"
          onClick={() => handleScroll("left")}
          className="alarm-page__filter-arrow ml-1"
        >
          <ChevronLeft className="h-4 w-4" />
        </button>
      </div>

      <div
        className={[
          "alarm-page__filter-fade alarm-page__filter-fade--right",
          canScrollRight ? "opacity-100" : "opacity-0",
        ].join(" ")}
      >
        <button
          type="button"
          aria-label="向右查看更多筛选项"
          onClick={() => handleScroll("right")}
          className="alarm-page__filter-arrow mr-1"
        >
          <ChevronRight className="h-4 w-4" />
        </button>
      </div>

      <div
        ref={scrollRef}
        className="alarm-page__filter-scroll no-scrollbar py-1"
      >
        <div className="alarm-page__filter-options">
          {options.map((option) => {
            const active = option.value === value;

            return (
              <button
                key={option.value}
                type="button"
                onClick={() => onChange(option.value)}
                className={`alarm-page__filter-button ${active ? "alarm-page__filter-button--active" : ""}`}
              >
                {option.label}
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
};

export default ScrollableFilter;
