package kmpworkshop.common


val emitNumber = coroutinePuzzleEndPoint<Unit, Int?>("Call emit(): Int")
val getAllUserIds = coroutinePuzzleEndPoint<Unit, List<Int>>("Call getAllUserIds(): List<Int>")
val queryUserById = coroutinePuzzleEndPoint<Int, SerializableUser?>("Call queryUserById(id: Int): User")
val queryExceptionThrown = coroutinePuzzleEndPoint<Unit, Unit>("Throw the exception given by queryUserWithCallback!")
val callLifetime = coroutinePuzzleEndPoint<Unit, Unit>("Call lifetime check (Done in scaffolding)")
val callIsDone = coroutinePuzzleEndPoint<Unit, Unit>("Finish the execution of your function")
val legacyCancellationCompletion = coroutinePuzzleEndPoint<Unit, Unit>("Wait for the legacy system to finish cancelling (Done in scaffolding)")
val getNumber = coroutinePuzzleEndPoint<Unit, Int>("Call getNumber(): Int")
val submitNumber = coroutinePuzzleEndPoint<Int, Unit>("Call submit(number: Int): Unit")
val emitFileToExpose = coroutinePuzzleEndPoint<Unit, FakeFileId>("Waiting for currentFileToExpose() emission")
val emitNetworkStrength = coroutinePuzzleEndPoint<Unit, NetworkStrength>("Waiting for new network strength change")
val openExposedFile = coroutinePuzzleEndPoint<FakeFileId, Unit>("Call FakeFile.open()")
val closeExposedFile = coroutinePuzzleEndPoint<FakeFileId, Unit>("Call FakeFile.close()")
val makeFileDownloadable = coroutinePuzzleEndPoint<FakeFileId, Unit>("Call makeDownloadable(FakeFile)")
val advertiseExposedFile = coroutinePuzzleEndPoint<FakeFileId, Unit>("Call advertiseFile(FakeFile)")
