"""검증용 합성 데이터 생성기.

실제 데이터는 반출할 수 없으므로, 유착 패턴을 일부러 심은 가짜 데이터를 만들어
탐지기가 실제로 반응하는지 먼저 확인한다. 아래 SEEDED에 심은 항목이 결과
상위에 올라오면 프로그램이 제대로 도는 것이다.

    python -m insurance_audit.make_sample --rows 40000 --out 샘플.csv
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

from . import console

# 일부러 심어 둔 유착 패턴. 분석 결과와 대조하는 정답지 역할을 한다.
SEEDED = {
    "배당편중": "담당자 H0007 → 법인 V03 (75%)",
    "결재결탁": "H0042 + 결재자 A003 → 법인 V07, 당일결재 90%",
    "조사자고정": "담당자 H0100 → 법인 V04 안에서 조사자 I0402 고정",
    "결과편향": "법인 V11 전부지급 비율 과다",
    "금액이상": "법인 V15 지급액 45% 과다",
    "지급처반복": "예금주 P99999 가 서로 다른 피보험자 건으로 반복 수령",
    "구상포기": "법인 V20 구상률 급감",
    "처리속도": "법인 V08 처리일수 급단축",
    "인물이동": "담당자 H0150 이 B03→B11→B17 로 옮겨도 법인 V09 편중 유지",
    "조직전환": "지점 B05 에서 2025년 팀장 교체와 함께 주력이 V02 → V17 로 이동",
}

COVERS = [("대인", "C1"), ("대물", "C2"), ("재물", "C3")]
CAUSES = [("차량단독", "A1"), ("차대차", "A2"), ("화재", "A3"), ("누수", "A4"), ("기타", "A9")]
OUTCOMES = [("전부지급", "R1"), ("일부지급", "R2"), ("면책", "R3"), ("취소", "R4")]
METHODS = [("현장조사", "M1"), ("서면조사", "M2"), ("의료자문", "M3")]
DAMAGE = [("부상", "D1"), ("물손", "D2"), ("전손", "D3")]

HEADERS = [
    "기준년", "청구번호", "피보험자", "피보험자id", "상품", "상품코드", "증권번호",
    "보험기간(시작)", "보험기간(종료)", "사고일", "담보구분", "담보구분코드",
    "사고정보", "사고기본정보코드", "사고원인", "사고원인코드", "대표재해", "대표재해코드",
    "소속1", "소속1 코드", "소속2", "소속2 코드", "소속3", "소속3코드",
    "보상담당", "보상담당자 사번", "조사결과", "조사결과코드", "손사법인", "손사법인코드",
    "조사자", "조사자코드", "조사방법", "조사방법코드", "구성여부", "구상여부코드",
    "청구서열", "청구자", "청구자번호id", "피해자서열", "피해자명", "피해자번호id",
    "피해유형", "피해유형코드", "대인최종합의금", "대물최종합의금", "결의서",
    "지급담보명", "지급담보코드", "지급보험금", "지급일", "지급처구분", "지급처구분 코드",
    "지급처", "지급처번호", "계좌주구분", "예금주", "예금주번호id",
    "담당자 결재상신 일자", "차상위 결재자명", "차상위결재자 사번", "차상위 결재자 일자",
    "하위클레임번호", "지급처순번", "적재일",
]


def generate(n_cases: int = 40_000, seed: int = 20260921) -> pd.DataFrame:
    rng = np.random.default_rng(seed)

    n_branches, handlers_per_branch, n_vendors = 20, 15, 25
    handler_ids = [f"H{index:04d}" for index in range(n_branches * handlers_per_branch)]
    branch_of = {h: f"B{index // handlers_per_branch:02d}" for index, h in enumerate(handler_ids)}
    approver_of_branch = {f"B{b:02d}": [f"A{b * 2:03d}", f"A{b * 2 + 1:03d}"] for b in range(n_branches)}
    vendor_ids = [f"V{index:02d}" for index in range(n_vendors)]

    handler = rng.choice(handler_ids, n_cases)
    branch = np.array([branch_of[h] for h in handler])

    # 연도를 먼저 정해야 발령과 팀장 교체를 연도에 걸 수 있다.
    accident = np.datetime64("2023-01-01") + rng.integers(0, 1095, n_cases).astype("timedelta64[D]")
    year = accident.astype("datetime64[Y]").astype(int) + 1970

    # --- 심는 패턴 9: 소속을 옮겨도 따라다니는 업체 -----------------------
    # 해마다 다른 지점으로 발령이 나지만 위임처는 그대로다.
    posting = {2023: "B03", 2024: "B11", 2025: "B17"}
    mover = handler == "H0150"
    branch = np.where(mover, np.array([posting.get(y, "B03") for y in year]), branch)

    # --- 심는 패턴 10: 팀장 교체와 함께 지점 주력 업체가 바뀜 --------------
    # 결재자는 연도에 따라 갈리고, 그에 맞춰 위임처도 통째로 옮겨간다.
    regime = branch == "B05"
    approver = np.array([approver_of_branch[b][rng.integers(0, 2)] for b in branch])
    approver = np.where(regime, np.where(year <= 2024, "A010", "A011"), approver)

    vendor = rng.choice(vendor_ids, n_cases)
    vendor = np.where(
        regime & (rng.random(n_cases) < 0.62),
        np.where(year <= 2024, "V02", "V17"),
        vendor,
    )
    vendor = np.where(mover & (rng.random(n_cases) < 0.70), "V09", vendor)

    # --- 심는 패턴 1: 특정 담당자의 법인 편중 -----------------------------
    focus = handler == "H0007"
    vendor = np.where(focus & (rng.random(n_cases) < 0.75), "V03", vendor)

    # --- 심는 패턴 2: 담당자-결재자-법인 3자 결탁 -------------------------
    triple = handler == "H0042"
    approver = np.where(triple, "A003", approver)
    vendor = np.where(triple & (rng.random(n_cases) < 0.80), "V07", vendor)

    # --- 심는 패턴 3: 법인 안에서 조사자 고정 -----------------------------
    # 법인 배당은 눈에 띄지 않게 두되(60%), 그 안에서 조사자 한 명에게 몰아준다.
    # 조합 검정에 최소 건수가 필요하므로 법인 배당을 먼저 충분히 깔아 준다.
    routed = handler == "H0100"
    vendor = np.where(routed & (rng.random(n_cases) < 0.60), "V04", vendor)
    fixed = routed & (vendor == "V04")
    investigator_slot = rng.integers(0, 5, n_cases)
    investigator_slot = np.where(fixed & (rng.random(n_cases) < 0.85), 2, investigator_slot)
    investigator = np.array(
        [f"I{v[1:]}{slot:02d}" for v, slot in zip(vendor, investigator_slot)]
    )

    cover_idx = rng.integers(0, len(COVERS), n_cases)
    cause_idx = rng.integers(0, len(CAUSES), n_cases)
    damage_idx = rng.integers(0, len(DAMAGE), n_cases)
    method_idx = rng.integers(0, len(METHODS), n_cases)

    # --- 심는 패턴 4: 특정 법인의 조사결과 편향 ---------------------------
    outcome_idx = rng.choice(len(OUTCOMES), n_cases, p=[0.45, 0.30, 0.20, 0.05])
    biased = vendor == "V11"
    outcome_idx = np.where(biased & (rng.random(n_cases) < 0.75), 0, outcome_idx)

    # --- 심는 패턴 5: 특정 법인의 지급액 과다 -----------------------------
    base_amount = rng.lognormal(mean=14.2, sigma=0.9, size=n_cases)
    base_amount = np.where(vendor == "V15", base_amount * 1.45, base_amount)
    amount = np.round(base_amount / 1000).astype(np.int64) * 1000

    # --- 심는 패턴 7: 특정 법인의 구상 포기 -------------------------------
    subrogation = rng.random(n_cases) < 0.30
    subrogation = np.where(vendor == "V20", rng.random(n_cases) < 0.04, subrogation)

    # --- 심는 패턴 8: 특정 법인의 처리 속도 단축 --------------------------
    duration = rng.integers(12, 70, n_cases)
    duration = np.where(vendor == "V08", rng.integers(1, 6, n_cases), duration)
    submit = accident + duration.astype("timedelta64[D]")

    approval_lag = rng.integers(0, 6, n_cases)
    # 3자 결탁 라인은 상신 당일 결재된다.
    approval_lag = np.where(triple & (vendor == "V07") & (rng.random(n_cases) < 0.90), 0, approval_lag)
    approve = submit + approval_lag.astype("timedelta64[D]")
    pay = approve + rng.integers(1, 12, n_cases).astype("timedelta64[D]")

    insured_id = np.array([f"P{index:06d}" for index in rng.integers(0, 300_000, n_cases)])

    # --- 심는 패턴 6: 제3자 예금주 반복 수령 ------------------------------
    payee_id = insured_id.copy()
    third_party = rng.random(n_cases) < 0.25
    payee_id = np.where(
        third_party,
        np.array([f"P{index:06d}" for index in rng.integers(500_000, 520_000, n_cases)]),
        payee_id,
    )
    repeat_target = (handler == "H0007") & (rng.random(n_cases) < 0.55)
    payee_id = np.where(repeat_target, "P99999", payee_id)

    cases = pd.DataFrame(
        {
            "청구번호": [f"CL{index:07d}" for index in range(n_cases)],
            "담당자": handler,
            "소속": branch,
            "결재자": approver,
            "법인": vendor,
            "조사자": investigator,
            "담보": cover_idx,
            "원인": cause_idx,
            "피해": damage_idx,
            "조사방법": method_idx,
            "결과": outcome_idx,
            "금액": amount,
            "구상": subrogation,
            "사고일": accident,
            "상신일": submit,
            "결재일": approve,
            "지급일": pay,
            "피보험자id": insured_id,
            "예금주id": payee_id,
        }
    )

    return _expand_to_payments(cases, rng)


def _expand_to_payments(cases: pd.DataFrame, rng: np.random.Generator) -> pd.DataFrame:
    """한 청구건을 담보·지급처 단위 여러 행으로 펼친다.

    실제 반출 데이터가 이 형태라, 분석 단위를 접는 로직이 제대로 도는지
    확인하려면 샘플도 같은 형태여야 한다.
    """
    splits = rng.choice([1, 2, 3, 4], len(cases), p=[0.45, 0.30, 0.17, 0.08])
    rows = cases.loc[cases.index.repeat(splits)].reset_index(drop=True)
    rows["지급처순번"] = rows.groupby("청구번호").cumcount() + 1

    share = rng.dirichlet(np.ones(4), len(rows))[:, 0] + 0.3
    rows["지급보험금"] = np.round(rows["금액"] * share / splits.repeat(splits) / 1000) * 1000
    rows["지급보험금"] = rows["지급보험금"].clip(lower=10_000).astype(np.int64)

    is_liability = rows["담보"] == 0
    rows["대인최종합의금"] = np.where(is_liability, rows["금액"], 0)
    rows["대물최종합의금"] = np.where(rows["담보"] == 1, rows["금액"], 0)

    frame = pd.DataFrame(
        {
            "기준년": rows["사고일"].dt.year,
            "청구번호": rows["청구번호"],
            "피보험자": "피보험자" + rows["피보험자id"].str[-4:],
            "피보험자id": rows["피보험자id"],
            "상품": "종합보험",
            "상품코드": "PR01",
            "증권번호": "PL" + rows["청구번호"].str[2:],
            "보험기간(시작)": (rows["사고일"] - pd.Timedelta(days=200)).dt.strftime("%Y%m%d"),
            "보험기간(종료)": (rows["사고일"] + pd.Timedelta(days=165)).dt.strftime("%Y%m%d"),
            "사고일": rows["사고일"].dt.strftime("%Y%m%d"),
            "담보구분": [COVERS[i][0] for i in rows["담보"]],
            "담보구분코드": [COVERS[i][1] for i in rows["담보"]],
            "사고정보": "일반사고",
            "사고기본정보코드": "B1",
            "사고원인": [CAUSES[i][0] for i in rows["원인"]],
            "사고원인코드": [CAUSES[i][1] for i in rows["원인"]],
            "대표재해": "일반재해",
            "대표재해코드": "E1",
            "소속1": "보상본부",
            "소속1 코드": "HQ",
            "소속2": "지역단" + rows["소속"].str[1:2],
            "소속2 코드": "RG" + rows["소속"].str[1:2],
            "소속3": rows["소속"] + "지점",
            "소속3코드": rows["소속"],
            "보상담당": "담당" + rows["담당자"].str[1:],
            "보상담당자 사번": rows["담당자"],
            "조사결과": [OUTCOMES[i][0] for i in rows["결과"]],
            "조사결과코드": [OUTCOMES[i][1] for i in rows["결과"]],
            "손사법인": rows["법인"] + "손해사정",
            "손사법인코드": rows["법인"],
            "조사자": "조사" + rows["조사자"].str[1:],
            "조사자코드": rows["조사자"],
            "조사방법": [METHODS[i][0] for i in rows["조사방법"]],
            "조사방법코드": [METHODS[i][1] for i in rows["조사방법"]],
            # 원본 반출 파일의 머리글 오타를 그대로 재현한다.
            "구성여부": np.where(rows["구상"], "Y", "N"),
            "구상여부코드": np.where(rows["구상"], "1", "0"),
            "청구서열": 1,
            "청구자": "청구자" + rows["피보험자id"].str[-4:],
            "청구자번호id": rows["피보험자id"],
            "피해자서열": rows["지급처순번"],
            "피해자명": "피해자" + rows["피보험자id"].str[-4:],
            "피해자번호id": rows["피보험자id"],
            "피해유형": [DAMAGE[i][0] for i in rows["피해"]],
            "피해유형코드": [DAMAGE[i][1] for i in rows["피해"]],
            "대인최종합의금": rows["대인최종합의금"],
            "대물최종합의금": rows["대물최종합의금"],
            "결의서": "RS" + rows["청구번호"].str[2:],
            "지급담보명": [COVERS[i][0] for i in rows["담보"]],
            "지급담보코드": [COVERS[i][1] for i in rows["담보"]],
            "지급보험금": rows["지급보험금"],
            "지급일": rows["지급일"].dt.strftime("%Y%m%d"),
            "지급처구분": np.where(rows["예금주id"] == rows["피보험자id"], "본인", "제3자"),
            "지급처구분 코드": np.where(rows["예금주id"] == rows["피보험자id"], "1", "2"),
            "지급처": "지급처" + rows["예금주id"].str[-4:],
            "지급처번호": rows["예금주id"],
            "계좌주구분": np.where(rows["예금주id"] == rows["피보험자id"], "본인", "타인"),
            "예금주": "예금주" + rows["예금주id"].str[-4:],
            "예금주번호id": rows["예금주id"],
            "담당자 결재상신 일자": rows["상신일"].dt.strftime("%Y%m%d"),
            "차상위 결재자명": "결재" + rows["결재자"].str[1:],
            "차상위결재자 사번": rows["결재자"],
            "차상위 결재자 일자": rows["결재일"].dt.strftime("%Y%m%d"),
            "하위클레임번호": rows["청구번호"] + "-" + rows["지급처순번"].astype(str),
            "지급처순번": rows["지급처순번"],
            "적재일": "20260901",
        }
    )
    return frame[HEADERS]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="검증용 합성 데이터 생성")
    parser.add_argument("--rows", type=int, default=40_000, help="청구 건수 (행은 그 2~3배)")
    parser.add_argument("--out", default="샘플_조사배당.csv", help="저장 경로")
    parser.add_argument("--encoding", default="utf-8-sig", help="저장 인코딩")
    args = parser.parse_args(argv)

    console.setup()
    frame = generate(args.rows)
    path = Path(args.out)
    path.parent.mkdir(parents=True, exist_ok=True)
    frame.to_csv(path, index=False, encoding=args.encoding)

    print(f"{len(frame):,}행 생성 → {path}")
    print("\n심어 둔 패턴 (분석 결과와 대조):")
    for key, text in SEEDED.items():
        print(f"  - {key}: {text}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
