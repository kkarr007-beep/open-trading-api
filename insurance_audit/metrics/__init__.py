"""탐지 지표 A~H."""

from __future__ import annotations

from . import (
    a_assignment,
    b_outcome,
    c_amount,
    d_approval,
    e_investigator,
    f_payee,
    g_subrogation,
    h_speed,
)

# 실행 순서. 보고서 시트 순서도 이 순서를 따른다.
MODULES = (
    a_assignment,
    b_outcome,
    c_amount,
    d_approval,
    e_investigator,
    f_payee,
    g_subrogation,
    h_speed,
)

__all__ = ["MODULES"]
