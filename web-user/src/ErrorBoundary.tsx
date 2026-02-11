import React from "react";

type ErrorBoundaryState = {
  hasError: boolean;
  message: string;
};

export class ErrorBoundary extends React.Component<React.PropsWithChildren, ErrorBoundaryState> {
  constructor(props: React.PropsWithChildren) {
    super(props);
    this.state = { hasError: false, message: "" };
  }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return {
      hasError: true,
      message: error.message
    };
  }

  override componentDidCatch(error: Error): void {
    // Safe client logging only: no payload/body/token details.
    console.error("ui_error", { message: error.message });
  }

  override render() {
    if (this.state.hasError) {
      return (
        <div style={{ padding: 20, fontFamily: "sans-serif" }}>
          <h2>예상하지 못한 인터페이스 오류가 발생했습니다</h2>
          <p>{this.state.message || "문제가 발생했습니다."}</p>
          <button onClick={() => window.location.reload()}>다시 시도</button>
        </div>
      );
    }

    return this.props.children;
  }
}
