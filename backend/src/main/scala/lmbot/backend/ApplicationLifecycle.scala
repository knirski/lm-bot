package lmbot.backend

private[backend] object ApplicationLifecycle:

  def closeAll(resources: List[AutoCloseable]): Unit =
    val primary = resources.foldLeft(Option.empty[Throwable]):
      case (failure, resource) =>
        try
          resource.close()
          failure
        catch
          case cleanup: Throwable =>
            failure match
              case None        => Some(cleanup)
              case Some(first) =>
                if cleanup ne first then first.addSuppressed(cleanup)
                failure

    primary.foreach(error => throw error)

  def closeAfterFailure(
      resources: List[AutoCloseable],
      primary: Throwable
  ): Unit =
    resources.foreach: resource =>
      try resource.close()
      catch
        case cleanup: Throwable =>
          if cleanup ne primary then primary.addSuppressed(cleanup)

  def withCleanupOnFailure[A](
      resources: List[AutoCloseable]
  )(operation: => A): A =
    try operation
    catch
      case primary: Throwable =>
        closeAfterFailure(resources, primary)
        throw primary

  def installShutdownHook(
      resources: List[AutoCloseable],
      register: Thread => Unit
  ): Unit =
    val hook = Thread: () =>
      closeAll(resources)

    try register(hook)
    catch
      case primary: Throwable =>
        closeAfterFailure(resources, primary)
        throw primary
