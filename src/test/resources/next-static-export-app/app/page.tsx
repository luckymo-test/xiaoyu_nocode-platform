export default function HomePage() {
  return (
    <main className="mx-auto flex min-h-screen max-w-5xl flex-col justify-center px-6 py-20">
      <p className="mb-4 text-sm uppercase tracking-[0.3em] text-amber-300">
        Next.js static export
      </p>
      <h1 className="max-w-3xl font-serif text-6xl leading-none text-stone-50 md:text-8xl">
        Built outside the API process.
      </h1>
      <p className="mt-8 max-w-xl text-lg leading-8 text-stone-300">
        This page verifies App Router, Tailwind CSS 4 and static export in the
        controlled StackProfile build path.
      </p>
    </main>
  )
}
