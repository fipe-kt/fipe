import FipeState
import SwiftUI

@MainActor
public struct WorkflowScreen<Model, Content: View>: View {
    private struct Mount {
        let owner: Lifetime
        let model: Model
    }

    private let makeWorkflow: (Lifetime) -> Model
    private let content: (Model) -> Content
    @State private var mounted: Mount?

    public init(
        makeWorkflow: @escaping (Lifetime) -> Model,
        @ViewBuilder content: @escaping (Model) -> Content
    ) {
        self.makeWorkflow = makeWorkflow
        self.content = content
    }

    public var body: some View {
        ZStack {
            if let mounted {
                content(mounted.model)
            }
        }
        .onAppear {
            guard mounted == nil else { return }
            let owner = Lifetime()
            mounted = Mount(owner: owner, model: makeWorkflow(owner))
        }
        .onDisappear {
            let retired = mounted
            mounted = nil
            retired?.owner.close()
        }
    }
}
