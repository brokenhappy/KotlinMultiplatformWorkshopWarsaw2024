package kmpworkshop.common

import kmpworkshop.api.FakeFileId
import kmpworkshop.api.NetworkStrength

object DefaultApis : EndpointDescriptorRegistry() {
    val emitNumber by flowDescriptor<Unit, Int>("numbers()")
    val getAllUserIds by descriptor<Unit, List<Int>>("Call getAllUserIds(): List<Int>")
    val queryUserById by descriptor<Int, SerializableUser>("Call queryUserById(id: Int): User")
    val queryExceptionThrown by descriptor<Unit, Unit>("Throw the exception given by queryUserWithCallback!")
    val callLifetime by descriptor<Unit, Unit>("Call lifetime check (Done in scaffolding)")
    val callIsDone by descriptor<Unit, Unit>("Finish the execution of your function")
    val legacyCancellationCompletion by descriptor<Unit, Unit>("Wait for the legacy system to finish cancelling (Done in scaffolding)")
    val getNumber by descriptor<Unit, Int>("Call getNumber(): Int")
    val submitNumber by descriptor<Int, Unit>("Call submit(number: Int): Unit")
    val emitFileToExpose by flowDescriptor<Unit, FakeFileId>("currentFileToExpose()")
    val emitNetworkStrength by flowDescriptor<Unit, NetworkStrength>("networkStrength()")
    val openExposedFile by descriptor<FakeFileId, Unit>("Call FakeFile.open()")
    val closeExposedFile by descriptor<FakeFileId, Unit>("Call FakeFile.close()")
    val makeFileDownloadable by descriptor<FakeFileId, Unit>("Call makeDownloadable(FakeFile)")
    val advertiseExposedFile by descriptor<FakeFileId, Unit>("Call advertiseFile(FakeFile)")
    val clearCachesEndpoint by descriptor<Unit, Unit>("clearCaches()")
    val refreshTokensEndpoint by descriptor<Unit, Unit>("refreshTokens()")
    val reportExceptionEndpoint by descriptor<String?, Unit>("reportException(e: Exception)")
    init { seal() }
}
