#!/usr/bin/env bash
# 사용법: source scripts/gradle-env.sh. 진단만 필요하면 이 파일을 --info로 실행한다.
# 전체 온라인 CPU 수로 워커를 설정하되 실제 실행 범위는 Android의 affinity/cgroup에 따른다.

_logent_cpu_count() {
    awk -F, '{
        for (i = 1; i <= NF; i++) {
            n = split($i, span, "-")
            count += n == 2 ? span[2] - span[1] + 1 : ($i != "")
        }
    } END { print count + 0 }' <<< "$1"
}

# 컨트롤러의 실제 마운트와 현재 프로세스 경로를 찾는다. Android의 /dev/cpuset도 포함한다.
_logent_cgroup_path() {
    local controller="$1" group root mount
    group="$(awk -F: -v c="$controller" '
        ($2 == "" && c == "v2") || index("," $2 ",", "," c ",") { print $3; exit }
    ' /proc/self/cgroup 2>/dev/null)"
    [[ -n "$group" ]] || return 1
    read -r root mount < <(awk -v c="$controller" '
        {
            for (i = 7; i <= NF; i++) {
                if ($i != "-") continue
                if ((c == "v2" && $(i+1) == "cgroup2") ||
                    ($(i+1) == "cgroup" && index("," $(i+3) ",", "," c ","))) {
                    print $4, $5; exit
                }
            }
        }
    ' /proc/self/mountinfo 2>/dev/null) || return 1
    [[ -n "$mount" ]] || return 1
    LOGENT_CGROUP_MOUNT="$mount"
    if [[ "$root" != / && "$group" == "$root" ]]; then
        LOGENT_CGROUP_PATH="$mount"
    elif [[ "$root" == / ]]; then
        LOGENT_CGROUP_PATH="${mount}${group%/}"
    elif [[ "$group" == "$root/"* ]]; then
        LOGENT_CGROUP_PATH="${mount}${group#"$root"}"
    else
        return 1
    fi
}

_logent_collect_resources() {
    local online affinity directory mount limit current remaining quota period cpus file
    if [[ ! -r /proc/meminfo || ! -r /proc/self/status ]]; then
        echo "/proc 메모리·CPU 정보를 읽을 수 없습니다. Android/컨테이너의 읽기 권한을 확인하세요." >&2
        return 1
    fi
    LOGENT_TOTAL_MB="$(awk '/^MemTotal:/ { print int($2 / 1024) }' /proc/meminfo)"
    LOGENT_AVAILABLE_MB="$(awk '/^MemAvailable:/ { print int($2 / 1024) }' /proc/meminfo)"
    LOGENT_SWAP_MB="$(awk '/^SwapFree:/ { print int($2 / 1024) }' /proc/meminfo)"
    LOGENT_SWAP_TOTAL_MB="$(awk '/^SwapTotal:/ { print int($2 / 1024) }' /proc/meminfo)"
    if [[ ! "$LOGENT_TOTAL_MB" =~ ^[0-9]+$ || ! "$LOGENT_AVAILABLE_MB" =~ ^[0-9]+$ ||
          ! "$LOGENT_SWAP_MB" =~ ^[0-9]+$ ]]; then
        echo "/proc/meminfo의 전체·가용 메모리 및 스왑 수치를 확인할 수 없습니다." >&2
        return 1
    fi
    online="$(cat /sys/devices/system/cpu/online 2>/dev/null || true)"
    LOGENT_ONLINE_CPUS="$(_logent_cpu_count "$online")"
    if (( LOGENT_ONLINE_CPUS == 0 )); then
        LOGENT_ONLINE_CPUS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || true)"
    fi
    if [[ ! "$LOGENT_ONLINE_CPUS" =~ ^[1-9][0-9]*$ ]]; then
        echo "온라인 CPU 수를 확인할 수 없습니다. /sys/devices/system/cpu/online 접근 권한을 확인하세요." >&2
        return 1
    fi
    affinity="$(awk '/^Cpus_allowed_list:/ { print $2 }' /proc/self/status)"
    LOGENT_AFFINITY_LIST="${affinity:-확인불가}"
    LOGENT_AFFINITY_CPUS="$(_logent_cpu_count "$affinity")"
    LOGENT_WORKERS="$LOGENT_ONLINE_CPUS"
    LOGENT_EFFECTIVE_CPUS="$LOGENT_AFFINITY_CPUS"
    LOGENT_CPU_QUOTA_MILLI=0
    LOGENT_CPUSET="읽기 불가 또는 미노출"
    LOGENT_CGROUP_MEMORY="읽기 불가 또는 제한 미노출"
    LOGENT_CGROUP_SWAP="읽기 불가 또는 제한 미노출"
    LOGENT_MEMORY_BUDGET_MB="$LOGENT_AVAILABLE_MB"
    LOGENT_SWAP_BUDGET_MB="$LOGENT_SWAP_MB"

    # 상위 cgroup 제한도 적용된다. 읽을 수 없는 제한은 추정값으로 꾸미지 않는다.
    if _logent_cgroup_path v2; then
        directory="$LOGENT_CGROUP_PATH"
        mount="$LOGENT_CGROUP_MOUNT"
        while [[ "$directory" == "$mount" || "$directory" == "$mount/"* ]]; do
            if [[ -r "$directory/cpuset.cpus.effective" ]]; then
                cpus="$(cat "$directory/cpuset.cpus.effective")"
                if [[ -n "$cpus" ]]; then
                    LOGENT_CPUSET="$cpus"
                    remaining="$(_logent_cpu_count "$cpus")"
                    if (( remaining < LOGENT_EFFECTIVE_CPUS )); then LOGENT_EFFECTIVE_CPUS="$remaining"; fi
                fi
            fi
            if [[ -r "$directory/cpu.max" ]]; then
                read -r quota period < "$directory/cpu.max" || true
                if [[ "$quota" =~ ^[0-9]+$ && "$period" =~ ^[1-9][0-9]*$ ]]; then
                    remaining=$(( quota * 1000 / period ))
                    if (( LOGENT_CPU_QUOTA_MILLI == 0 || remaining < LOGENT_CPU_QUOTA_MILLI )); then
                        LOGENT_CPU_QUOTA_MILLI="$remaining"
                    fi
                fi
            fi
            for file in memory memory.swap; do
                if [[ -r "$directory/$file.max" && -r "$directory/$file.current" ]]; then
                    limit="$(cat "$directory/$file.max")"
                    current="$(cat "$directory/$file.current")"
                    if [[ "$file" == memory ]]; then
                        LOGENT_CGROUP_MEMORY="읽기 완료"
                    else
                        LOGENT_CGROUP_SWAP="읽기 완료"
                    fi
                    if [[ "$limit" =~ ^[0-9]+$ && "$current" =~ ^[0-9]+$ ]]; then
                        remaining=$(( (limit - current) / 1048576 ))
                        (( remaining >= 0 )) || remaining=0
                        if [[ "$file" == memory ]]; then
                            if (( remaining < LOGENT_MEMORY_BUDGET_MB )); then LOGENT_MEMORY_BUDGET_MB="$remaining"; fi
                        else
                            if (( remaining < LOGENT_SWAP_BUDGET_MB )); then LOGENT_SWAP_BUDGET_MB="$remaining"; fi
                        fi
                    fi
                fi
            done
            [[ "$directory" != "$mount" ]] || break
            directory="${directory%/*}"
        done
    fi
    if _logent_cgroup_path cpuset; then
        for file in cpuset.effective_cpus effective_cpus cpuset.cpus cpus; do
            if [[ -r "$LOGENT_CGROUP_PATH/$file" ]]; then
                cpus="$(cat "$LOGENT_CGROUP_PATH/$file")"
                if [[ -n "$cpus" ]]; then
                    LOGENT_CPUSET="$cpus"
                    remaining="$(_logent_cpu_count "$cpus")"
                    if (( remaining < LOGENT_EFFECTIVE_CPUS )); then LOGENT_EFFECTIVE_CPUS="$remaining"; fi
                    break
                fi
            fi
        done
    fi
    if _logent_cgroup_path cpu; then
        directory="$LOGENT_CGROUP_PATH"
        mount="$LOGENT_CGROUP_MOUNT"
        while [[ "$directory" == "$mount" || "$directory" == "$mount/"* ]]; do
            if [[ -r "$directory/cpu.cfs_quota_us" && -r "$directory/cpu.cfs_period_us" ]]; then
                quota="$(cat "$directory/cpu.cfs_quota_us")"
                period="$(cat "$directory/cpu.cfs_period_us")"
                if [[ "$quota" =~ ^[0-9]+$ && "$period" =~ ^[1-9][0-9]*$ ]]; then
                    remaining=$(( quota * 1000 / period ))
                    if (( LOGENT_CPU_QUOTA_MILLI == 0 || remaining < LOGENT_CPU_QUOTA_MILLI )); then
                        LOGENT_CPU_QUOTA_MILLI="$remaining"
                    fi
                fi
            fi
            [[ "$directory" != "$mount" ]] || break
            directory="${directory%/*}"
        done
    fi
    if _logent_cgroup_path memory; then
        directory="$LOGENT_CGROUP_PATH"
        mount="$LOGENT_CGROUP_MOUNT"
        while [[ "$directory" == "$mount" || "$directory" == "$mount/"* ]]; do
            if [[ -r "$directory/memory.limit_in_bytes" && -r "$directory/memory.usage_in_bytes" ]]; then
                limit="$(cat "$directory/memory.limit_in_bytes")"
                current="$(cat "$directory/memory.usage_in_bytes")"
                if [[ "$limit" =~ ^[0-9]+$ && "$current" =~ ^[0-9]+$ ]]; then
                    LOGENT_CGROUP_MEMORY="읽기 완료"
                    remaining=$(( (limit - current) / 1048576 ))
                    (( remaining >= 0 )) || remaining=0
                    if (( remaining < LOGENT_MEMORY_BUDGET_MB )); then LOGENT_MEMORY_BUDGET_MB="$remaining"; fi
                fi
            fi
            if [[ -r "$directory/memory.memsw.limit_in_bytes" && -r "$directory/memory.memsw.usage_in_bytes" ]]; then
                limit="$(cat "$directory/memory.memsw.limit_in_bytes")"
                current="$(cat "$directory/memory.memsw.usage_in_bytes")"
                if [[ "$limit" =~ ^[0-9]+$ && "$current" =~ ^[0-9]+$ ]]; then
                    LOGENT_CGROUP_SWAP="메모리+스왑 통합 한도 읽기 완료"
                    remaining=$(( (limit - current) / 1048576 - LOGENT_MEMORY_BUDGET_MB ))
                    (( remaining >= 0 )) || remaining=0
                    if (( remaining < LOGENT_SWAP_BUDGET_MB )); then LOGENT_SWAP_BUDGET_MB="$remaining"; fi
                fi
            fi
            [[ "$directory" != "$mount" ]] || break
            directory="${directory%/*}"
        done
    fi
    if (( LOGENT_CPU_QUOTA_MILLI > 0 )); then
        remaining=$(( (LOGENT_CPU_QUOTA_MILLI + 999) / 1000 ))
        if (( remaining < LOGENT_EFFECTIVE_CPUS )); then LOGENT_EFFECTIVE_CPUS="$remaining"; fi
    fi
}

_logent_show_resources() {
    echo "CPU: 전체 온라인 ${LOGENT_ONLINE_CPUS}개, 프로세스 affinity ${LOGENT_AFFINITY_CPUS}개 (${LOGENT_AFFINITY_LIST})"
    echo "cgroup cpuset: ${LOGENT_CPUSET}, 확인 가능한 실행 CPU 상한 ${LOGENT_EFFECTIVE_CPUS}개"
    if (( LOGENT_CPU_QUOTA_MILLI > 0 )); then
        echo "cgroup CPU 시간 할당량: ${LOGENT_CPU_QUOTA_MILLI}/1000 코어 상당"
    else
        echo "cgroup CPU 시간 할당량: 읽을 수 있는 유한 한도 없음 (미노출·권한 제한 가능)"
    fi
    echo "워커: 전체 온라인 CPU 기준 ${LOGENT_WORKERS}개; Android cpuset·affinity·시간 할당량을 우회하지 않음"
    echo "메모리: 전체 ${LOGENT_TOTAL_MB}MB, 현재 가용 ${LOGENT_AVAILABLE_MB}MB"
    echo "스왑: 전체 ${LOGENT_SWAP_TOTAL_MB}MB, 현재 여유 ${LOGENT_SWAP_MB}MB (RAM과 속도·성능이 같지 않음)"
    echo "cgroup 메모리: ${LOGENT_CGROUP_MEMORY}; 스왑: ${LOGENT_CGROUP_SWAP}"
    echo "현재 예산: 메모리 ${LOGENT_MEMORY_BUDGET_MB}MB + 스왑 ${LOGENT_SWAP_BUDGET_MB}MB"
}

_logent_gradle_env() {
    _logent_collect_resources || return 1
    _logent_show_resources
    # 전체 메모리의 45%를 기준으로 하되 메타스페이스와 OS 여유를 각각 남긴다.
    LOGENT_METASPACE_MB=1024
    LOGENT_RESERVE_MB=1024
    LOGENT_HEAP_MB=$(( LOGENT_TOTAL_MB * 45 / 100 ))
    LOGENT_BUDGET_MB=$(( LOGENT_MEMORY_BUDGET_MB + LOGENT_SWAP_BUDGET_MB - LOGENT_METASPACE_MB - LOGENT_RESERVE_MB ))
    if (( LOGENT_BUDGET_MB < LOGENT_HEAP_MB )); then LOGENT_HEAP_MB="$LOGENT_BUDGET_MB"; fi
    if (( LOGENT_HEAP_MB < 1536 )); then
        echo "사용 가능한 메모리가 부족합니다: 안전 여유·메타스페이스를 제외한 힙 예산 ${LOGENT_HEAP_MB}MB (최소 1536MB)." >&2
        echo "다른 앱·빌드 작업을 종료하고 ./scripts/device-info.sh로 자원을 확인한 뒤 다시 실행하세요." >&2
        return 1
    fi
    LOGENT_GRADLE_JVMARGS="-Xmx${LOGENT_HEAP_MB}m -XX:MaxMetaspaceSize=${LOGENT_METASPACE_MB}m -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"
    export LOGENT_GRADLE_JVMARGS LOGENT_WORKERS LOGENT_HEAP_MB
    LOGENT_GRADLE_ARGS=(
        "--parallel"
        "--max-workers=${LOGENT_WORKERS}"
        "-Dorg.gradle.jvmargs=${LOGENT_GRADLE_JVMARGS}"
    )
    echo "Gradle 자원: 힙 ${LOGENT_HEAP_MB}MB, 메타스페이스 ${LOGENT_METASPACE_MB}MB, 안전 여유 ${LOGENT_RESERVE_MB}MB, 병렬 실행"
}

if [[ "${BASH_SOURCE[0]}" == "$0" && "${1:-}" == --info ]]; then
    _logent_collect_resources && _logent_show_resources
else
    _logent_gradle_env
fi
