#!/bin/bash

# Default values
AGENT_TYPES=("Greedy")
AGENT_COUNTS=(1)
SIMULATOR="Simulator:helper.SimulatorAgent"

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -t|--type)
            IFS=',' read -r -A AGENT_TYPES <<< "$2"
            shift 2
            ;;
        -n|--num)
            IFS=',' read -r -A AGENT_COUNTS <<< "$2"
            shift 2
            ;;
        -h|--help)
            echo "Usage: ./run.sh [OPTIONS]"
            echo "Options:"
            echo "  -t, --type TYPE1,TYPE2,...    Agent types (e.g., Greedy,Random,AStar)"
            echo "  -n, --num  NUM1,NUM2,...      Number of agents per type (default: 1 for each type)"
            echo "  -h, --help                     Show this help message"
            echo ""
            echo "Examples:"
            echo "  ./run.sh -t Greedy -n 3                  # 3 Greedy agents"
            echo "  ./run.sh -t Greedy,Random -n 2,3         # 2 Greedy + 3 Random agents"
            echo "  ./run.sh -t Greedy,Random,AStar -n 1,2,1 # 1 Greedy + 2 Random + 1 AStar"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# If counts array is shorter than types, fill with 1s
while [[ ${#AGENT_COUNTS[@]} -lt ${#AGENT_TYPES[@]} ]]; do
    AGENT_COUNTS+=(1)
done

# Build agent list
AGENTS="$SIMULATOR"
agent_counter=1

for idx in {1..${#AGENT_TYPES[@]}}; do
    agent_type="${AGENT_TYPES[$idx]}"
    num_agents="${AGENT_COUNTS[$idx]}"
    
    for i in $(seq 1 "$num_agents"); do
        AGENTS="$AGENTS;${agent_type}_${agent_counter}:agents.${agent_type}Agent()"
        ((agent_counter++))
    done
done
# template: AGENTS="Simulator:helper.SimulatorAgent;Greedy_1:agents.GreedyAgent();Random_2:agents.RandomAgent();AStar_3:agents.AStarAgent()"
echo "Running with agents: $AGENTS"
# Run JADE
java -cp "lib/jade.jar:classes" jade.Boot -agents "$AGENTS"
