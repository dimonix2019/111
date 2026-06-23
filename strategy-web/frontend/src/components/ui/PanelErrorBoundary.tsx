import { Component, type ErrorInfo, type ReactNode } from 'react'

type Props = { children: ReactNode; title?: string }
type State = { error: Error | null }

export class PanelErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[PanelErrorBoundary]', error, info.componentStack)
  }

  render() {
    if (this.state.error) {
      return (
        <div className="rounded-xl border border-bad/30 bg-[rgba(209,122,136,0.08)] p-4">
          <div className="text-[13px] font-semibold text-bad">{this.props.title ?? 'Ошибка отображения'}</div>
          <p className="mt-2 text-[12px] text-ink-2">{this.state.error.message}</p>
          <button type="button" className="tab-btn mt-3" onClick={() => this.setState({ error: null })}>
            Попробовать снова
          </button>
        </div>
      )
    }
    return this.props.children
  }
}
