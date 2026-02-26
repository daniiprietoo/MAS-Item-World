#!/usr/bin/env python3
"""
Experiment runner for Item World Multi-Agent Simulation (JADE).
Runs 10 repetitions per configuration, each with a different seed,
and computes average scores.

Usage:
    python run_experiments.py --scenario 1   # Fixed world, no traps
    python run_experiments.py --scenario 2   # Fixed world, 10 traps
    python run_experiments.py --scenario 3   # Dynamic world, no traps
    python run_experiments.py --scenario 4   # Dynamic world, 10 traps
    python run_experiments.py                # Runs all 4 scenarios (slow!)

Each repetition uses seed = (scenario_id * 1000 + rep), so results are
varied across reps but fully reproducible if you run the script again.

Requires:
  - Modified Config.java       (mutable SEED / USE_SEED)
  - Modified SimulatorAgent.java (4th arg = seed)
  Recompile with:
    javac -d classes -cp "lib/jade.jar:src" src/helper/*.java src/agents/*.java src/config/*.java
"""

import subprocess
import re
import os
import argparse
import platform
from collections import defaultdict
from typing import Dict, List, Tuple, Optional

# ============================================================
# GLOBAL CONFIG — edit if needed
# ============================================================
IS_WINDOWS = platform.system() == "Windows"
JADE_CP = r"lib\jade.jar;classes" if IS_WINDOWS else "lib/jade.jar:classes"
NUM_REPS = 10  # repetitions per configuration
TIMEOUT_SEC = 360  # max seconds per single run

AGENT_TYPES = ["Random", "Greedy", "AStar"]

# Scenario definitions:
#   redistrib_every = 10000  =>  never redistributes in a 1000-round sim  (fixed)
#   redistrib_every = 10     =>  redistributes every 10 rounds             (dynamic)
SCENARIOS = {
    1: {"label": "Fixed world, no traps", "num_traps": 0, "redistrib_every": 10000},
    2: {"label": "Fixed world, 10 traps", "num_traps": 10, "redistrib_every": 10000},
    3: {"label": "Dynamic world, no traps", "num_traps": 0, "redistrib_every": 10},
    4: {"label": "Dynamic world, 10 traps", "num_traps": 10, "redistrib_every": 10},
}
# ============================================================


def build_agent_string(
    agent_configs: List[Tuple[str, int]],  # [(agent_type, commitment), ...]
    num_traps: int,
    redistrib_every: int,
    seed: int,
) -> str:
    """Build the JADE -agents string, including the seed as the 4th Simulator arg."""
    num_participants = len(agent_configs)
    simulator = (
        f"Simulator:helper.SimulatorAgent"
        f"({num_traps},{redistrib_every},{num_participants},{seed})"
    )
    parts = [simulator]
    for i, (agent_type, commitment) in enumerate(agent_configs, start=1):
        parts.append(f"{agent_type}_{i}:agents.{agent_type}Agent({commitment})")
    return ";".join(parts)


def run_simulation(agent_string: str) -> str:
    """Run one simulation, return combined stdout+stderr."""
    cmd = ["java", "-cp", JADE_CP, "jade.Boot", "-agents", agent_string]
    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=TIMEOUT_SEC,
            cwd=os.path.dirname(os.path.abspath(__file__)),
        )
        return result.stdout + result.stderr
    except subprocess.TimeoutExpired:
        print("      [WARN] Timed out!", flush=True)
        return ""
    except Exception as exc:
        print(f"      [ERROR] {exc}", flush=True)
        return ""


def parse_final_scores(output: str) -> Dict[str, int]:
    """
    Extract final scores from the last 'SIMULATION ENDED!!!' block.
    Participant.toString() prints:
        Name: <agentLocalName>
        ...
        Score: <int>
    """
    marker = "SIMULATION ENDED!!!"
    idx = output.rfind(marker)
    section = output[idx:] if idx != -1 else output

    scores: Dict[str, int] = {}
    for name, score in re.findall(
        r"Name:\s*(\S+).*?Score:\s*(-?\d+)", section, re.DOTALL
    ):
        if "Simulator" not in name:
            scores[name] = int(score)
    return scores


def run_config(
    agent_configs: List[Tuple[str, int]],
    num_traps: int,
    redistrib_every: int,
    label: str,
    scenario_id: int,
    config_index: int,  # used to build unique seeds across configs
) -> Dict[str, List[int]]:
    """
    Run NUM_REPS repetitions. Each rep uses a deterministic but unique seed:
        seed = scenario_id * 100_000 + config_index * 1_000 + rep
    Returns {agent_type: [score_rep1, score_rep2, ...]}.
    """
    scores_by_type: Dict[str, List[int]] = defaultdict(list)
    print(f"\n    [{label}]", flush=True)

    for rep in range(1, NUM_REPS + 1):
        seed = scenario_id * 100_000 + config_index * 1_000 + rep
        agent_string = build_agent_string(
            agent_configs, num_traps, redistrib_every, seed
        )
        print(f"      rep {rep:2d}/{NUM_REPS}  seed={seed}  ... ", end="", flush=True)

        output = run_simulation(agent_string)
        if not output:
            print("no output", flush=True)
            continue

        scores = parse_final_scores(output)
        if not scores:
            print("no scores parsed", flush=True)
            continue

        # Map instance name (e.g. "Greedy_2") back to type (e.g. "Greedy")
        for agent_name, score in scores.items():
            m = re.match(r"([A-Za-z]+)", agent_name)
            if m:
                scores_by_type[m.group(1)].append(score)

        print(f"scores={dict(scores)}", flush=True)

    return dict(scores_by_type)


def average(values: List[int]) -> Optional[float]:
    return round(sum(values) / len(values), 1) if values else None


def fmt(val: Optional[float]) -> str:
    return f"{val:>8.1f}" if val is not None else "     N/A"


def run_scenario(scenario_id: int) -> None:
    sc = SCENARIOS[scenario_id]
    num_traps = sc["num_traps"]
    redistrib = sc["redistrib_every"]

    print(f"\n{'='*70}")
    print(f"SCENARIO {scenario_id}: {sc['label']}")
    print(f"  traps={num_traps}, redistrib_every={redistrib}, reps={NUM_REPS}")
    print(f"{'='*70}")

    results: Dict[str, Dict[str, Optional[float]]] = {t: {} for t in AGENT_TYPES}
    config_idx = 0  # incremented per sub-config so seeds never collide

    # --- Each agent type alone, commitment 1 and 20 ---
    for commitment in [1, 20]:
        for atype in AGENT_TYPES:
            col = f"alone_c{commitment}"
            data = run_config(
                [(atype, commitment)],
                num_traps,
                redistrib,
                label=f"{atype} alone, commitment={commitment}",
                scenario_id=scenario_id,
                config_index=config_idx,
            )
            results[atype][col] = average(data.get(atype, []))
            config_idx += 1

    # --- All 3 together, commitment 1 and 20 ---
    for commitment in [1, 20]:
        col = f"together_c{commitment}"
        configs = [(t, commitment) for t in AGENT_TYPES]
        data = run_config(
            configs,
            num_traps,
            redistrib,
            label=f"All 3 together, commitment={commitment}",
            scenario_id=scenario_id,
            config_index=config_idx,
        )
        for atype in AGENT_TYPES:
            results[atype][col] = average(data.get(atype, []))
        config_idx += 1

    # --- Print results table ---
    COL = 14
    header = (
        f"\n{'Agent Type':<12}"
        f"{'Alone c=1':>{COL}}"
        f"{'Alone c=20':>{COL}}"
        f"{'Together c=1':>{COL}}"
        f"{'Together c=20':>{COL}}"
    )
    sep = "-" * (12 + COL * 4)

    print(f"\n\nRESULTS — Scenario {scenario_id}: {sc['label']}")
    print(sep)
    print(header)
    print(sep)
    rows = []
    for atype in AGENT_TYPES:
        r = results[atype]
        row = (
            f"{atype:<12}"
            f"{fmt(r.get('alone_c1')):>{COL}}"
            f"{fmt(r.get('alone_c20')):>{COL}}"
            f"{fmt(r.get('together_c1')):>{COL}}"
            f"{fmt(r.get('together_c20')):>{COL}}"
        )
        print(row)
        rows.append(row)
    print(sep)

    # Save to file
    out_path = os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        f"results_scenario_{scenario_id}.txt",
    )
    with open(out_path, "w") as f:
        f.write(f"Scenario {scenario_id}: {sc['label']}\n")
        f.write(f"traps={num_traps}, redistrib_every={redistrib}, reps={NUM_REPS}\n")
        f.write(sep + "\n")
        f.write(header + "\n")
        f.write(sep + "\n")
        for row in rows:
            f.write(row + "\n")
        f.write(sep + "\n")

    print(f"\nSaved to: {out_path}")


def main():
    global NUM_REPS

    parser = argparse.ArgumentParser(
        description="Item World experiment runner",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Scenarios:
  1  Fixed world, no traps
  2  Fixed world, 10 traps
  3  Dynamic world, no traps  (redistribution every 10 rounds)
  4  Dynamic world, 10 traps  (redistribution every 10 rounds)

Examples:
  python run_experiments.py -s 1           # scenario 1, 10 reps
  python run_experiments.py -s 2 -r 3     # scenario 2, only 3 reps (quick test)
  python run_experiments.py                # all 4 scenarios
        """,
    )
    parser.add_argument(
        "--scenario",
        "-s",
        type=int,
        choices=[1, 2, 3, 4],
        help="Scenario to run (1-4). Omit to run all four.",
    )
    parser.add_argument(
        "--reps",
        "-r",
        type=int,
        default=NUM_REPS,
        help=f"Number of repetitions per configuration (default: {NUM_REPS})",
    )
    args = parser.parse_args()

    NUM_REPS = args.reps

    if args.scenario:
        run_scenario(args.scenario)
    else:
        print("Running ALL 4 scenarios — this will take a while!")
        for sid in [1, 2, 3, 4]:
            run_scenario(sid)


if __name__ == "__main__":
    main()
