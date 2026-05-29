import streamlit as st

from zsim import LOOKBACK_DAYS

DARK_CSS = """
<style>
    .stApp { background-color: #0b111b; color: #e5e7eb; }
    [data-testid="stSidebar"] { background-color: #111827; border-right: 1px solid #1f2937; }
    [data-testid="stMetric"] {
        background: #111827; border: 1px solid #1f2937; border-radius: 12px; padding: 12px 16px;
    }
    [data-testid="stMetricLabel"] { color: #94a3b8 !important; }
    [data-testid="stMetricValue"] { color: #fbbf24 !important; }
    .stTabs [data-baseweb="tab-list"] { gap: 8px; }
    .stTabs [data-baseweb="tab"] {
        background: #111827; border-radius: 8px; color: #94a3b8; border: 1px solid #1f2937;
    }
    .stTabs [aria-selected="true"] { background: #1f2937 !important; color: #fbbf24 !important; }
    div[data-testid="stButton"] > button[kind="primary"] {
        background: linear-gradient(90deg, #059669, #10b981); border: none; font-weight: 600;
    }
    .hero { color: #94a3b8; font-size: 0.95rem; margin-bottom: 1rem; }
    .badge-ok { color: #10b981; font-weight: 600; }
    .badge-warn { color: #fbbf24; font-weight: 600; }
</style>
"""


def setup_page() -> None:
    st.set_page_config(page_title="Z-Strategy Backtester", layout="wide", initial_sidebar_state="expanded")
    st.markdown(DARK_CSS, unsafe_allow_html=True)
    st.title("Z-Strategy Backtester")
    st.markdown(
        f'<p class="hero">TATN / TATNP · {LOOKBACK_DAYS}д · 15м · стиль ArbiCurs</p>',
        unsafe_allow_html=True,
    )
