import java.util.regex.Pattern

fun main() {
    val rawOutput = """##teamcity[enteredTheMatrix]
##teamcity[suiteTreeNode id='|[engine:junit-jupiter|]/|[class:nextop.sam2.customerspt.trace.TraceBuilderTest|]/|[method:testClear()|]' name='Test clear: Should clear all traced changes' nodeId='|[engine:junit-jupiter|]/|[class:nextop.sam2.customerspt.trace.TraceBuilderTest|]/|[method:testClear()|]' parentNodeId='0' locationHint='java:test://nextop.sam2.customerspt.trace.TraceBuilderTest/testClear' metainfo='']
##teamcity[testStarted id='|[engine:junit-jupiter|]/|[class:nextop.sam2.customerspt.trace.TraceBuilderTest|]/|[method:testClear()|]' name='Test clear: Should clear all traced changes' nodeId='|[engine:junit-jupiter|]/|[class:nextop.sam2.customerspt.trace.TraceBuilderTest|]/|[method:testClear()|]' parentNodeId='0' locationHint='java:test://nextop.sam2.customerspt.trace.TraceBuilderTest/testClear' metainfo='']
##teamcity[testFinished id='|[engine:junit-jupiter|]/|[class:nextop.sam2.customerspt.trace.TraceBuilderTest|]/|[method:testClear()|]' name='Test clear: Should clear all traced changes' nodeId='|[engine:junit-jupiter|]/|[class:nextop.sam2.customerspt.trace.TraceBuilderTest|]/|[method:testClear()|]' parentNodeId='0' duration='1494']
"""
    val MESSAGE_PATTERN = Regex("##teamcity\\[([a-zA-Z]+)(.*?)(?<!\\|)]")

    val matches = MESSAGE_PATTERN.findAll(rawOutput).toList()
    println("Matches: ${matches.size}")
    for (match in matches) {
        println("Match: ${match.value}")
        println("Group 1: ${match.groupValues[1]}")
        println("Group 2: ${match.groupValues[2]}")
    }
}
