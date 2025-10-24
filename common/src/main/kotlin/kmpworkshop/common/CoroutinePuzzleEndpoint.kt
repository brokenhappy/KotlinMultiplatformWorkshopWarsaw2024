package kmpworkshop.common


val emitNumber = coroutinePuzzleEndPoint<Unit, Int?>("call emit(): Int")
val getAllUserIds = coroutinePuzzleEndPoint<Unit, List<Int>>("call getAllUserIds(): List<Int>")
val queryUserById = coroutinePuzzleEndPoint<Int, SerializableUser?>("call queryUserById(id: Int): User")
val queryExceptionThrown = coroutinePuzzleEndPoint<Unit, Unit>("throw the exception given by queryUserWithCallback!")
val callLifetime = coroutinePuzzleEndPoint<Unit, Unit>("call lifetime check (Done in scaffolding)", isHiddenInHistory = true)
val callIsDone = coroutinePuzzleEndPoint<Unit, Unit>("finish the execution of your function")
val getNumber = coroutinePuzzleEndPoint<Unit, Int>("call getNumber(): Int")
val submitNumber = coroutinePuzzleEndPoint<Int, Unit>("call submit(number: Int): Unit")
