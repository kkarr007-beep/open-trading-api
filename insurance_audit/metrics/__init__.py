"""탐지 지표 A~H."""

from __future__ import annotations

from . import (
    a_assignment,
    b_outcome,
    c_amount,
    d_approval,
    e_investigator,
    g_subrogation,
    h_speed,
    i_mobility,
    j_regime,
)

# 실행 순서. 보고서 시트 순서도 이 순서를 따른다.
# F(지급처 반복)는 손사법인에 나가는 수수료라 유착 신호가 아니므로 뺀다.
MODULES = (
    a_assignment,
    b_outcome,
    c_amount,
    d_approval,
    e_investigator,
    g_subrogation,
    h_speed,
    i_mobility,
    j_regime,
)

__all__ = ["MODULES"]
