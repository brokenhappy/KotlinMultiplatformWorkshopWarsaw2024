package kmpworkshop.common


val emitNumber = CoroutinePuzzleEndPoint<Unit, Int?>("call emit(): Int")
val getAllUserIds = CoroutinePuzzleEndPoint<Unit, List<Int>>("call getAllUserIds(): List<Int>")
val queryUserById = CoroutinePuzzleEndPoint<Int, SerializableUser?>("call queryUserById(id: Int): User")
val queryExceptionThrown = CoroutinePuzzleEndPoint<Unit, Unit>("throw the exception given by queryUserWithCallback!")
val callLifetime = CoroutinePuzzleEndPoint<Unit, Unit>("call lifetime check (Done in scaffolding)")
val callIsDone = CoroutinePuzzleEndPoint<Unit, Unit>("finish the execution of your function")
val getNumber = CoroutinePuzzleEndPoint<Unit, Int>("call getNumber(): Int")
val submitNumber = CoroutinePuzzleEndPoint<Int, Unit>("call submit(number: Int): Unit")
val cancelSubmit = CoroutinePuzzleEndPoint<Unit, Unit>("cancel[submit(number: Int): Unit]")
