package kmpworkshop.common


val emitNumber = CoroutinePuzzleEndPoint<Unit, Int?>("call emit(): Int")
val getNumber = CoroutinePuzzleEndPoint<Unit, Int>("call getNumber(): Int")
val submitNumber = CoroutinePuzzleEndPoint<Int, Unit>("call submit(number: Int): Unit")
val cancelSubmit = CoroutinePuzzleEndPoint<Unit, Unit>("cancel[submit(number: Int): Unit]")
