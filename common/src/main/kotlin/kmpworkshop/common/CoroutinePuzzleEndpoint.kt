package kmpworkshop.common


val emitNumber = CoroutinePuzzleEndPoint<Unit, Int?>("call emit(): Int")
val getAllUserIds = CoroutinePuzzleEndPoint<Unit, List<Int>>("call getAllUserIds(): List<Int>")
val queryUserById = CoroutinePuzzleEndPoint<Int, SerializableUser>("call queryUserById(id: Int): User")
val getNumber = CoroutinePuzzleEndPoint<Unit, Int>("call getNumber(): Int")
val submitNumber = CoroutinePuzzleEndPoint<Int, Unit>("call submit(number: Int): Unit")
val cancelSubmit = CoroutinePuzzleEndPoint<Unit, Unit>("cancel[submit(number: Int): Unit]")
