#!/bin/bash
# P15 legacy reachability census — production tree only.
cd "$(dirname "$0")/.." || exit 1
PROD=app/src/main/java
TEST=app/src/test/java

TOKENS="UserProgress ProgramDayState cycleNumber ProgramConfiguration family_progression_state adaptive_decision_record AdaptiveRepository AdaptiveWorkoutIntegration AdaptiveSessionDecisionRecorder SessionHistoryAdapter SessionAdaptivePlanReader PilotProgressionProfiles ProgressionResolver AdaptiveProgramEngine AdaptivePolicy AdaptiveSignalCalculator AdaptiveSignals"

echo "############ PER-FILE HITS (production) ############"
for t in $TOKENS; do
  hits=$(grep -rl "$t" $PROD 2>/dev/null | wc -l)
  lines=$(grep -rho "$t" $PROD 2>/dev/null | wc -l)
  printf "%-36s files=%-4s lines=%-5s : " "$t" "$hits" "$lines"
  grep -rl "$t" $PROD 2>/dev/null | sed "s|$PROD/com/monkfitness/app/||" | tr '\n' ' '
  echo
done

echo
echo "############ SetLog / legacy entities in production ############"
grep -rn "class SetLog\|data class SetLog" $PROD 2>/dev/null
grep -rln "SetLog" $PROD 2>/dev/null | sed "s|$PROD/com/monkfitness/app/||" | sort

echo
echo "############ Room entities declared ############"
grep -rn "^@Entity" -A2 app/src/main/java/com/monkfitness/app/data/local/*.kt 2>/dev/null | grep -o 'tableName *= *"[a-z_]*"' | sed 's/.*"\(.*\)"/\1/' | sort | uniq -c

echo
echo "############ @Entity classes (file:codeline) ############"
grep -rn "@Entity" $PROD --include=*.kt | sed "s|$PROD/com/monkfitness/app/||"

echo
echo "############ AppDatabase entities/DAOs ############"
find $PROD -name "AppDatabase.kt" -exec grep -n "::class\|abstract fun\|version *=" {} \;
